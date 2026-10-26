package app.oguri.alarms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fired by AlarmManager (setAlarmClock) at the alarm time T. Starts the
 * foreground BackupService, which watches for phone use and rings at T+3
 * if the phone stayed untouched.
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String id = intent.getStringExtra(BackupScheduler.EXTRA_ALARM_ID);
        if (id == null) {
            return;
        }
        if (!AlarmStore.isEnabled(context, id)) {
            return;
        }
        Intent service = new Intent(context, BackupService.class);
        service.putExtra(BackupScheduler.EXTRA_ALARM_ID, id);
        context.startForegroundService(service);
    }
}
