package app.oguri.alarms;

import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;

/**
 * Thin wrapper over Gadgetbridge's Alarms Intent API. Gadgetbridge stays
 * installed and paired with the Honor Band 4; this app only sends broadcasts
 * and never touches Bluetooth itself.
 *
 * The API must be enabled in Gadgetbridge (device specific settings for the
 * band, developer options: enable the Alarms API).
 */
public final class GadgetbridgeApi {

    public static final String PACKAGE = "nodomain.freeyourgadget.gadgetbridge";
    public static final String ACTION_SET_ALARM =
            "nodomain.freeyourgadget.gadgetbridge.command.SET_ALARM";
    public static final String ACTION_DISMISS_ALARM =
            "nodomain.freeyourgadget.gadgetbridge.command.DISMISS_ALARM";

    /** MAC of the Honor Band 4, as Gadgetbridge knows it. */
    public static final String DEVICE = "D0:16:B4:DC:87:10";

    private GadgetbridgeApi() {
    }

    /**
     * Creates or replaces a band alarm.
     *
     * @param days ArrayList of java.util.Calendar day constants
     *             (Sunday 1 .. Saturday 7). Callers always pass a list
     *             (AlarmStore.effectiveDaysMask is never empty); null would
     *             omit the extra entirely and is not used.
     */
    public static void setAlarm(Context ctx, String title, int hour, int minutes,
                                ArrayList<Integer> days) {
        Intent intent = new Intent(ACTION_SET_ALARM);
        intent.setPackage(PACKAGE);
        intent.putExtra("device", DEVICE);
        intent.putExtra("hour", hour);
        intent.putExtra("minutes", minutes);
        if (days != null) {
            intent.putIntegerArrayListExtra("days", days);
        }
        intent.putExtra("title", title);
        ctx.sendBroadcast(intent);
    }

    /** Removes the band alarm stored under the given title. */
    public static void dismissAlarm(Context ctx, String title) {
        Intent intent = new Intent(ACTION_DISMISS_ALARM);
        intent.setPackage(PACKAGE);
        intent.putExtra("mode", "title");
        intent.putExtra("title", title);
        ctx.sendBroadcast(intent);
    }
}
