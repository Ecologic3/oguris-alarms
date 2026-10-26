package app.oguri.alarms;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/**
 * Arms and disarms the phone-backup trigger for an alarm. The trigger is an
 * AlarmManager alarm-clock intent fired at the alarm's next occurrence; it is
 * received by {@link AlarmReceiver}, which starts the watching service.
 */
public final class BackupScheduler {

    public static final String ACTION_TRIGGER = "app.oguri.alarms.TRIGGER";
    public static final String EXTRA_ALARM_ID = "alarm_id";

    private BackupScheduler() {
    }

    /**
     * Computes the next occurrence (epoch millis) of an alarm at hour:minute:
     * the next day whose Calendar day-of-week bit is set in the mask wins.
     * Callers pass AlarmStore.effectiveDaysMask, which is never empty
     * (Dynamic and day-less fixed alarms resolve to all 7 days); the empty
     * mask fallback (today if ahead, else tomorrow) is only a safety net.
     */
    public static long nextOccurrenceMillis(int hour, int minute, int daysMask) {
        Calendar now = Calendar.getInstance();
        for (int offset = 0; offset < 8; offset++) {
            Calendar candidate = (Calendar) now.clone();
            candidate.add(Calendar.DAY_OF_YEAR, offset);
            candidate.set(Calendar.HOUR_OF_DAY, hour);
            candidate.set(Calendar.MINUTE, minute);
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);
            if (candidate.getTimeInMillis() <= now.getTimeInMillis()) {
                continue;
            }
            if (daysMask == 0) {
                return candidate.getTimeInMillis();
            }
            int dayOfWeek = candidate.get(Calendar.DAY_OF_WEEK);
            if ((daysMask & (1 << dayOfWeek)) != 0) {
                return candidate.getTimeInMillis();
            }
        }
        // Fallback (should not happen): tomorrow at the same time.
        Calendar fallback = (Calendar) now.clone();
        fallback.add(Calendar.DAY_OF_YEAR, 1);
        fallback.set(Calendar.HOUR_OF_DAY, hour);
        fallback.set(Calendar.MINUTE, minute);
        fallback.set(Calendar.SECOND, 0);
        fallback.set(Calendar.MILLISECOND, 0);
        return fallback.getTimeInMillis();
    }

    /** (Re)arms the backup trigger for an enabled alarm. No-op if disabled. */
    public static void arm(Context ctx, String id) {
        if (!AlarmStore.isEnabled(ctx, id)) {
            return;
        }
        int mask = AlarmStore.effectiveDaysMask(ctx, id);
        long when = nextOccurrenceMillis(
                AlarmStore.getHour(ctx, id), AlarmStore.getMinute(ctx, id), mask);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent showIntent = new Intent(ctx, MainActivity.class);
        showIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent showPi = PendingIntent.getActivity(ctx, 9001, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showPi),
                    triggerPendingIntent(ctx, id));
        } catch (SecurityException e) {
            // Exact-alarm permission missing; the setup section in the UI
            // walks the user through granting it.
        }
    }

    /** Cancels a pending backup trigger (alarm toggled off). */
    public static void disarm(Context ctx, String id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = triggerPendingIntent(ctx, id);
        am.cancel(pi);
        pi.cancel();
    }

    private static PendingIntent triggerPendingIntent(Context ctx, String id) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.setAction(ACTION_TRIGGER);
        intent.putExtra(EXTRA_ALARM_ID, id);
        return PendingIntent.getBroadcast(ctx, requestCodeFor(id), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int requestCodeFor(String id) {
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
        // Alarms added later carry ids u1, u2, ... and get their own codes
        // so their backup triggers never collide with the original four.
        if (id != null && id.startsWith("u")) {
            try {
                return 200 + Integer.parseInt(id.substring(1));
            } catch (NumberFormatException ignored) {
                // Fall through to the hash based code below.
            }
        }
        return 300 + (Math.abs(id == null ? 0 : id.hashCode()) % 500);
    }
}
