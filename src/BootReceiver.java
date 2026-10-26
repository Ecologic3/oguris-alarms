package app.oguri.alarms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-arms the backup triggers for all enabled alarms after a reboot
 * (AlarmManager intents do not survive a restart).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        for (String id : AlarmStore.getIds(context)) {
            BackupScheduler.arm(context, id);
        }
    }
}
