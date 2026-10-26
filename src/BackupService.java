package app.oguri.alarms;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * The phone sound backup. Started at the band alarm time T by
 * AlarmReceiver.
 *
 * Behaviour:
 * - Posts a low-key "sound backup armed" notification with a Stop action.
 * - Activity model: ANY real activity on the phone means the user is awake
 *   and cancels the backup. "In use" is defined as the screen being
 *   interactive (PowerManager.isInteractive) while the keyguard is NOT
 *   locked. A notification lighting the screen onto the lock screen is
 *   NOT activity: the keyguard stays locked, so notifications can never
 *   cancel the backup by themselves.
 * - An unlock (ACTION_USER_PRESENT) cancels instantly, because it is the
 *   one activity that always has a broadcast. But an unlock that happened
 *   before T produces no broadcast inside the window (that is how 1.4
 *   rang at T+3 while the user was using the phone: he had unlocked seconds
 *   before T and kept using it), so the in-use state is also polled:
 *   once immediately when the watch starts, then every 5 seconds for
 *   the whole service lifetime, and once more as a final gate before
 *   ringing. ACTION_SCREEN_ON is received too, but only as a trigger
 *   for one in-use check about a second later, never as activity by
 *   itself: a power-button or fingerprint wake that ends in an unlocked
 *   phone is caught fast, a lock-screen notification peek is not.
 * - Records every outcome (service started, rang at T+3, cancelled by
 *   unlock, cancelled: phone in use, stopped by user, hard cap reached,
 *   playback failure) with a timestamp through
 *   AlarmStore.setLastBackupOutcome, and the app shows the latest line
 *   in its Setup section.
 * - At T+3 minutes, if the phone was never used, it plays the selected
 *   alarm sound on a loop. Ringing stops when the phone is used, when the
 *   notification's Stop action is tapped, or after a hard cap of
 *   3 minutes.
 *
 * Timing (changed in 1.6): the T+3 ring trigger and the 3-minute ring
 * cap are AlarmManager exact alarms (setAlarmClock, the same mechanism
 * family BackupScheduler uses for the trigger at T), delivered to
 * BackupTimerReceiver, which starts this service with ACTION_RING or
 * ACTION_CAP. They are NOT Handler.postDelayed timers: Handler delays
 * run on SystemClock.uptimeMillis, which pauses while the device is
 * in deep sleep (suspend), so on a locked, untouched phone the
 * 1.5 ring timer had still not fired at T+4 and the window only ended
 * when he unlocked the phone and the unlock broadcast arrived. That
 * is the recorded root cause of the 1.5 untouched-phone failure, and
 * the same flaw had silently defeated the ring in 1.3. AlarmManager
 * wall-clock alarms wake the device, so the ring and the cap fire on
 * time whether the phone sleeps or not.
 *
 * Because the process can die during the window, the window itself is
 * persisted in AlarmStore (active flag plus alarm id, set when a watch
 * starts and cleared in finishWindow). A ring or cap delivery acts
 * only when that persisted state names the same alarm id; a delivery
 * that finds no active window does nothing, and in particular never
 * starts ringing. The 5-second in-use poll and the screen-on debounce
 * stay on the Handler: they only matter while the phone is awake, and
 * their results are advisory next to the unlock broadcast and the
 * final in-use gate inside startRinging().
 *
 * Ring-stop hardening (1.7): on 1.6 the user unlocked the phone while the
 * backup was ringing and the ring did not stop; he had to tap Stop.
 * The unlock broadcast and the poll are both correct on paper, but on
 * the ringing instance the poll still ran at the 5-second watch
 * cadence on the uptime clock, so after the deep-sleep window its
 * next run could lag seconds behind his interaction, and interaction
 * that never dismisses the keyguard (the lock-screen shade) produces
 * neither an unlock broadcast nor an "in use" state at all. While
 * ringing, the poll therefore runs every second (the device is awake
 * while it rings, so Handler timing is reliable there), and opening
 * the app while a backup rings stops it outright: the ringing state
 * is persisted in AlarmStore and MainActivity sends ACTION_STOP on
 * resume when it finds that flag set.
 *
 * When the window ends, the alarm re-arms for its next occurrence and
 * stays enabled. Dynamic is a daily alarm (all 7 days) just like the fixed
 * ones; nothing switches itself off.
 */
public class BackupService extends Service {

    public static final String ACTION_STOP = "app.oguri.alarms.STOP_BACKUP";
    public static final String ACTION_RING = "app.oguri.alarms.RING_BACKUP";
    public static final String ACTION_CAP = "app.oguri.alarms.CAP_BACKUP";

    private static final String CHANNEL_ID = "sound_backup";
    private static final String LEGACY_CHANNEL_ID = "backup";
    private static final int NOTIFICATION_ID = 42;
    private static final long WATCH_MS = 3L * 60L * 1000L;
    private static final long MAX_RING_MS = 3L * 60L * 1000L;
    private static final long IN_USE_POLL_MS = 5000L;
    private static final long RING_IN_USE_POLL_MS = 1000L;
    private static final long SCREEN_ON_CHECK_DELAY_MS = 1000L;

    // PendingIntent request codes for the AlarmManager ring/cap timers.
    // BackupScheduler owns 101-104 (the original alarms), 200+n
    // (user-added alarms) and 300-799 (its hash fallback) for the
    // trigger alarms at T, and the notification PendingIntents use
    // 9001-9003, so the timers take the same per-alarm index as
    // BackupScheduler but offset into their own ranges: 5000+index for
    // the ring timer, 6000+index for the cap timer. They can never
    // collide with a trigger or a notification PendingIntent.
    private static final int RING_CODE_BASE = 5000;
    private static final int CAP_CODE_BASE = 6000;

    private Handler mHandler;
    private String mAlarmId;
    private boolean mUsed;
    private boolean mFinished;
    private boolean mRinging;
    /** True when this instance itself started (or took over) the window. */
    private boolean mWindowLive;
    private MediaPlayer mMediaPlayer;
    private BroadcastReceiver mInteractionReceiver;

    /**
     * Repeating in-use poll: runs until the window finishes. The
     * cadence is 5 seconds during the watch and 1 second while
     * ringing (see the class doc): a ringing phone is awake, so the
     * faster cadence costs nothing and catches an unlock within about
     * a second even if the unlock broadcast is somehow missed.
     */
    private final Runnable mInUsePollRunnable = new Runnable() {
        @Override
        public void run() {
            checkPhoneInUse(false);
            if (!mFinished) {
                mHandler.postDelayed(this,
                        mRinging ? RING_IN_USE_POLL_MS : IN_USE_POLL_MS);
            }
        }
    };

    /** One-shot in-use check scheduled shortly after ACTION_SCREEN_ON. */
    private final Runnable mScreenOnCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkPhoneInUse(false);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (mAlarmId == null) {
                // A fresh instance (the process died mid-window) adopts
                // the persisted window's id, so the stop still cancels
                // the right timers and re-arms the right alarm.
                mAlarmId = AlarmStore.getBackupWindowAlarmId(this);
            }
            if (mAlarmId == null && !mWindowLive) {
                // Nothing live here and no persisted window: this start
                // only exists to consume a stale ringing flag (the
                // process died mid-ring and MainActivity found the flag
                // set). Clear it and go away without recording an
                // outcome for a window this instance never saw.
                AlarmStore.setBackupRinging(this, false);
                stopSelf();
                return START_NOT_STICKY;
            }
            finishWindow(mRinging || AlarmStore.isBackupRinging(this)
                    ? "rang at T+3, stopped by user"
                    : "stopped by user before ringing");
            return START_NOT_STICKY;
        }
        if (intent != null && (ACTION_RING.equals(intent.getAction())
                || ACTION_CAP.equals(intent.getAction()))) {
            return onTimerCommand(intent);
        }
        if (intent != null) {
            String id = intent.getStringExtra(BackupScheduler.EXTRA_ALARM_ID);
            if (id != null) {
                mAlarmId = id;
            }
        }
        if (mAlarmId == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(false));
        AlarmStore.setLastBackupOutcome(this,
                "service started, watching for unlock (" + alarmName() + ")");
        // Persist the window before anything else can finish it: the
        // AlarmManager ring/cap timers are delivered to whatever
        // process is alive then, and this state is all they may act on.
        AlarmStore.setBackupWindow(this, mAlarmId);
        // A new watch also retires any stale ringing flag left behind
        // by a process that died mid-ring in an earlier window.
        AlarmStore.setBackupRinging(this, false);
        mWindowLive = true;
        registerInteractionReceiver();
        // A phone already unlocked and in hand at T means the user is awake:
        // an unlock before T leaves no broadcast inside the window.
        checkPhoneInUse(false);
        if (mFinished) {
            return START_NOT_STICKY;
        }
        mHandler.removeCallbacks(mInUsePollRunnable);
        mHandler.postDelayed(mInUsePollRunnable, IN_USE_POLL_MS);
        scheduleTimer(ACTION_RING, WATCH_MS);
        return START_NOT_STICKY;
    }

    /**
     * Handles an AlarmManager timer delivery (ACTION_RING at T+3,
     * ACTION_CAP 3 minutes after ringing started). The persisted
     * window in AlarmStore is the authority: if it does not name this
     * alarm, the delivery is stale (the window already finished, or
     * this is a leftover timer) and it must do nothing, above all
     * never start ringing. When the persisted window is active but
     * this instance never saw the watch (the process died and was
     * restarted by the timer), the instance takes the window over, so
     * the ring and the cap still land exactly once.
     */
    private int onTimerCommand(Intent intent) {
        String id = intent.getStringExtra(BackupScheduler.EXTRA_ALARM_ID);
        if (id != null) {
            mAlarmId = id;
        }
        // startForegroundService was used to reach us, so the
        // foreground call is owed even on the do-nothing path.
        startForeground(NOTIFICATION_ID, buildNotification(mRinging));
        if (id == null || !id.equals(AlarmStore.getBackupWindowAlarmId(this))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CAP.equals(intent.getAction())) {
            // The cap applies when this instance is ringing, or when a
            // restarted instance finds the window still persisted: in
            // that case an earlier incarnation was ringing when the
            // process died (the cap is only ever scheduled from
            // startRinging), and closing the window here records the
            // outcome, re-arms and clears the state.
            if (!mFinished && (mRinging || !mWindowLive)) {
                finishWindow("rang at T+3, hard cap reached");
            }
            return START_NOT_STICKY;
        }
        // ACTION_RING. A restarted instance takes over the window's
        // interaction watching too, so an unlock can still stop the
        // ring it is about to start.
        registerInteractionReceiver();
        if (!mWindowLive) {
            mWindowLive = true;
            mHandler.removeCallbacks(mInUsePollRunnable);
            mHandler.postDelayed(mInUsePollRunnable, IN_USE_POLL_MS);
        }
        if (!mFinished && !mRinging) {
            // startRinging keeps its final in-use gate, so an unlock
            // that lands at the same wake moment still wins.
            startRinging();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- timers

    /**
     * Schedules one AlarmManager timer (ring or cap) for this window.
     * setAlarmClock is a wall-clock wakeup alarm, so it fires on time
     * even while the device is in deep sleep, unlike a Handler delay.
     */
    private void scheduleTimer(String action, long delayMs) {
        if (mAlarmId == null) {
            return;
        }
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long when = System.currentTimeMillis() + delayMs;
        Intent showIntent = new Intent(this, MainActivity.class);
        showIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent showPi = PendingIntent.getActivity(this, 9001, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showPi),
                    timerPendingIntent(action));
        } catch (SecurityException e) {
            // Exact-alarm permission missing; the setup section in the
            // UI walks the user through granting it.
        }
    }

    /** Cancels any pending ring and cap timers for this window. */
    private void cancelTimers() {
        if (mAlarmId == null) {
            return;
        }
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        String[] actions = {ACTION_RING, ACTION_CAP};
        for (String action : actions) {
            PendingIntent pi = timerPendingIntent(action);
            am.cancel(pi);
            pi.cancel();
        }
    }

    private PendingIntent timerPendingIntent(String action) {
        Intent intent = new Intent(this, BackupTimerReceiver.class);
        intent.setAction(action);
        intent.putExtra(BackupScheduler.EXTRA_ALARM_ID, mAlarmId);
        return PendingIntent.getBroadcast(this, timerRequestCode(action, mAlarmId),
                intent, PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int timerRequestCode(String action, String id) {
        int base = ACTION_CAP.equals(action) ? CAP_CODE_BASE : RING_CODE_BASE;
        return base + timerIndexFor(id);
    }

    /**
     * The per-alarm index, mirroring BackupScheduler's request codes
     * (101-104 for the original alarms, 200+n for user-added ones, a
     * 300-799 hash fallback) so the timer codes stay parallel to the
     * trigger codes while living in their own offset ranges.
     */
    private static int timerIndexFor(String id) {
        if (AlarmStore.ID_A1.equals(id)) {
            return 101;
        }
        if (AlarmStore.ID_A2.equals(id)) {
            return 102;
        }
        if (AlarmStore.ID_A3.equals(id)) {
            return 103;
        }
        if (AlarmStore.ID_DYN.equals(id)) {
            return 104;
        }
        if (id != null && id.startsWith("u")) {
            try {
                return 200 + Integer.parseInt(id.substring(1));
            } catch (NumberFormatException ignored) {
                // Fall through to the hash based index below.
            }
        }
        return 300 + (Math.abs(id == null ? 0 : id.hashCode()) % 500);
    }

    // ---------------------------------------------------------------- watch

    private void registerInteractionReceiver() {
        if (mInteractionReceiver != null) {
            return;
        }
        mInteractionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : null;
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    // Never activity by itself (a notification lighting
                    // the lock screen fires this too): just schedule an
                    // in-use state check shortly after the wake settles.
                    mHandler.removeCallbacks(mScreenOnCheckRunnable);
                    mHandler.postDelayed(mScreenOnCheckRunnable,
                            SCREEN_ON_CHECK_DELAY_MS);
                } else {
                    onPhoneUsed();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mInteractionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mInteractionReceiver, filter);
        }
    }

    private void onPhoneUsed() {
        if (mFinished) {
            return;
        }
        mUsed = true;
        finishWindow(mRinging
                ? "rang at T+3, cancelled by unlock"
                : "cancelled by unlock");
    }

    /**
     * "In use" = the screen is interactive AND the keyguard is not
     * locked. A screen lit behind a locked keyguard (notification peek,
     * lock screen glance, always-on pulse) does not count, so a
     * notification can never look like the user being awake.
     */
    private boolean isPhoneInUse() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || !pm.isInteractive()) {
            return false;
        }
        KeyguardManager km =
                (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null) {
            return false;
        }
        return !km.isKeyguardLocked();
    }

    /**
     * Runs one in-use check and finishes the window if the phone is in
     * use. Returns true when the window finished (or was finished).
     * atRingGate selects the T+3 outcome wording used by startRinging.
     */
    private boolean checkPhoneInUse(boolean atRingGate) {
        if (mFinished) {
            return true;
        }
        if (!isPhoneInUse()) {
            return false;
        }
        mUsed = true;
        if (atRingGate) {
            finishWindow("cancelled: phone in use at T+3");
        } else if (mRinging) {
            finishWindow("rang at T+3, cancelled: phone in use");
        } else {
            finishWindow("cancelled: phone in use");
        }
        return true;
    }

    // ----------------------------------------------------------------- ring

    private void startRinging() {
        if (mFinished || mUsed) {
            return;
        }
        // Final gate: never start a sound while the phone is in use.
        if (checkPhoneInUse(true)) {
            return;
        }
        mRinging = true;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(true));
        }
        if (!startPlayer()) {
            finishWindow("failed to play backup sound");
            return;
        }
        // Persist the ringing state so MainActivity can stop the ring
        // if the user opens the app (see the class doc), and switch the
        // in-use poll to its 1-second ringing cadence right away
        // instead of whenever the 5-second watch chain was next due.
        AlarmStore.setBackupRinging(this, true);
        mHandler.removeCallbacks(mInUsePollRunnable);
        mHandler.postDelayed(mInUsePollRunnable, RING_IN_USE_POLL_MS);
        AlarmStore.setLastBackupOutcome(this, "rang at T+3 (" + alarmName() + ")");
        scheduleTimer(ACTION_CAP, MAX_RING_MS);
    }

    private boolean startPlayer() {
        releasePlayer();
        Uri uri = AlarmStore.getEffectiveBackupSoundUri(this);
        if (uri == null) {
            return false;
        }
        try {
            MediaPlayer player = new MediaPlayer();
            mMediaPlayer = player;
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    finishWindow("ringing stopped (player error)");
                    return true;
                }
            });
            player.prepare();
            player.start();
            return true;
        } catch (Exception e) {
            releasePlayer();
            return false;
        }
    }

    private void releasePlayer() {
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) {
                    mMediaPlayer.stop();
                }
            } catch (IllegalStateException ignored) {
                // The player may already be in an error or released state.
            }
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    // --------------------------------------------------------------- finish

    /** Ends the window with an outcome derived from the current state. */
    private void finishWindow() {
        if (mUsed) {
            finishWindow("cancelled by unlock");
        } else if (mRinging) {
            finishWindow("rang at T+3, stopped");
        } else {
            finishWindow("finished");
        }
    }

    /**
     * Ends the backup window exactly once: records the outcome for the
     * in-app last-outcome line, cancels any pending ring/cap timers,
     * clears the persisted window and ringing flag, stops any ringing, re-arms the
     * alarm for its next occurrence, and stops the service.
     */
    private void finishWindow(String outcome) {
        if (mFinished) {
            return;
        }
        mFinished = true;
        if (outcome != null) {
            AlarmStore.setLastBackupOutcome(this,
                    outcome + " (" + alarmName() + ")");
        }
        cancelTimers();
        AlarmStore.clearBackupWindow(this);
        AlarmStore.setBackupRinging(this, false);
        cleanup();
        if (mAlarmId != null) {
            BackupScheduler.arm(this, mAlarmId);
        }
        stopSelf();
    }

    private String alarmName() {
        return AlarmStore.getName(this,
                mAlarmId != null ? mAlarmId : AlarmStore.ID_DYN);
    }

    private void cleanup() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        releasePlayer();
        if (mInteractionReceiver != null) {
            try {
                unregisterReceiver(mInteractionReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
            mInteractionReceiver = null;
        }
    }

    // ---------------------------------------------------------- notification

    private void createChannel() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        // Remove the pre-1.3 channel so its old description does not linger
        // in system settings after an in-place update.
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Alarm backup", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Phone sound backup for band alarms");
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(boolean ringing) {
        String title = alarmName();
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 9002, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, BackupService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 9003, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Oguri's Alarms")
                .setContentText(ringing
                        ? "Backup alarm ringing · tap Stop"
                        : "Sound backup armed for " + title)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi);
        return builder.build();
    }
}
