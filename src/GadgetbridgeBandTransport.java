package app.oguri.alarms;

import android.content.Context;

import app.oguri.alarms.band.EventAlarm;

import java.util.List;

/**
 * The frozen Gadgetbridge fallback behind the BandTransport boundary.
 *
 * This adapter deliberately uses GadgetbridgeApi exactly as that class
 * already exists in the 1.3 sources. It adds no extras, changes no
 * broadcast and fixes nothing in the API class; it only translates the
 * transport-neutral calls into the per-title calls the old UI used to
 * make directly:
 * - setAllAlarms: dismiss every currently stored title first (so a
 *   disabled, renamed or deleted alarm cannot linger on the band), then
 *   set each enabled alarm by title with its day list.
 * - dismissAllAlarms: dismiss every currently stored title.
 * - setTime: a no-op. Gadgetbridge syncs the band clock itself while it
 *   owns the connection, which is the behaviour this fallback shipped
 *   with.
 */
public final class GadgetbridgeBandTransport implements BandTransport {

    @Override
    public String name() {
        return "gadgetbridge";
    }

    @Override
    public void setAllAlarms(Context ctx, List<EventAlarm> alarms) {
        dismissAllAlarms(ctx);
        for (EventAlarm alarm : alarms) {
            GadgetbridgeApi.setAlarm(ctx, alarm.name, alarm.hour, alarm.minute,
                    BandAlarmMapper.calendarDaysList(alarm.repeat));
        }
    }

    @Override
    public void dismissAllAlarms(Context ctx) {
        for (String id : AlarmStore.getIds(ctx)) {
            GadgetbridgeApi.dismissAlarm(ctx, AlarmStore.getName(ctx, id));
        }
    }

    @Override
    public void setTime(Context ctx, long epochMillis, int offsetSeconds) {
        // No-op on this adapter: Gadgetbridge syncs the band time itself.
    }
}
