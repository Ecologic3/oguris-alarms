package app.oguri.alarms;

import android.content.Context;

import app.oguri.alarms.band.EventAlarm;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Converts between AlarmStore state and the transport-neutral band model
 * (app.oguri.alarms.band.EventAlarm), so the UI and the store never need
 * to know which transport is active.
 *
 * Day encodings differ: AlarmStore uses a bit per Calendar constant
 * (1 << dayOfWeek, Sunday 1 .. Saturday 7), while the band mask is
 * Mon 0x01, Tue 0x02, Wed 0x04, Thu 0x08, Fri 0x10, Sat 0x20, Sun 0x40.
 */
public final class BandAlarmMapper {

    private BandAlarmMapper() {
    }

    /** Calendar day constant to band bit, indexed by Calendar constant. */
    private static final int[] CALENDAR_TO_BAND = new int[8];

    static {
        CALENDAR_TO_BAND[Calendar.MONDAY] = EventAlarm.MON;
        CALENDAR_TO_BAND[Calendar.TUESDAY] = EventAlarm.TUE;
        CALENDAR_TO_BAND[Calendar.WEDNESDAY] = EventAlarm.WED;
        CALENDAR_TO_BAND[Calendar.THURSDAY] = EventAlarm.THU;
        CALENDAR_TO_BAND[Calendar.FRIDAY] = EventAlarm.FRI;
        CALENDAR_TO_BAND[Calendar.SATURDAY] = EventAlarm.SAT;
        CALENDAR_TO_BAND[Calendar.SUNDAY] = EventAlarm.SUN;
    }

    public static int bandRepeatMask(int calendarMask) {
        int band = 0;
        for (int day = Calendar.SUNDAY; day <= Calendar.SATURDAY; day++) {
            if ((calendarMask & (1 << day)) != 0) {
                band |= CALENDAR_TO_BAND[day];
            }
        }
        return band;
    }

    public static int calendarDaysMask(int bandRepeatMask) {
        int mask = 0;
        for (int day = Calendar.SUNDAY; day <= Calendar.SATURDAY; day++) {
            if ((bandRepeatMask & CALENDAR_TO_BAND[day]) != 0) {
                mask |= (1 << day);
            }
        }
        return mask;
    }

    /** Calendar day list for a band mask, for adapters that need it. */
    public static ArrayList<Integer> calendarDaysList(int bandRepeatMask) {
        return AlarmStore.daysList(calendarDaysMask(bandRepeatMask));
    }

    /**
     * The enabled alarms as a band list, in AlarmStore list order, with
     * band slots assigned sequentially from 1. Effective days are used,
     * so Dynamic is all 7 days and a day-less fixed alarm is all 7 days;
     * no alarm here ever has an empty day set.
     */
    public static List<EventAlarm> enabledAlarms(Context ctx) {
        List<EventAlarm> out = new ArrayList<EventAlarm>();
        int slot = 1;
        for (String id : AlarmStore.getIds(ctx)) {
            if (!AlarmStore.isEnabled(ctx, id)) {
                continue;
            }
            out.add(new EventAlarm(
                    slot++,
                    true,
                    AlarmStore.getHour(ctx, id),
                    AlarmStore.getMinute(ctx, id),
                    bandRepeatMask(AlarmStore.effectiveDaysMask(ctx, id)),
                    AlarmStore.getName(ctx, id)));
        }
        return out;
    }
}
