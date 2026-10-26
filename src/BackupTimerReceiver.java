package app.oguri.alarms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Delivers BackupService's AlarmManager timers: the T+3 ring trigger
 * (BackupService.ACTION_RING) and the 3-minute ring cap
 * (BackupService.ACTION_CAP). The timers live in AlarmManager rather
 * than on a Handler because Handler delays run on the uptime clock,
 * which pauses while the device is in deep sleep; that is why the 1.5
 * backup never rang on a locked, untouched phone.
 *
 * The persisted backup window in AlarmStore is checked here and again
 * in the service: a delivery acts only while a window is active for
 * the same alarm id, so a timer that outlives its window (or arrives
 * after the process died and the window was finished without it) does
 * nothing, and in particular never starts a ring.
 */
public class BackupTimerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!BackupService.ACTION_RING.equals(action)
                && !BackupService.ACTION_CAP.equals(action)) {
            return;
        }
        String id = intent.getStringExtra(BackupScheduler.EXTRA_ALARM_ID);
        if (id == null) {
            return;
        }
        if (!id.equals(AlarmStore.getBackupWindowAlarmId(context))) {
            return;
        }
        Intent service = new Intent(context, BackupService.class);
        service.setAction(action);
        service.putExtra(BackupScheduler.EXTRA_ALARM_ID, id);
        context.startForegroundService(service);
    }
}
