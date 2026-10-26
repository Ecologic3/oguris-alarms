package app.oguri.alarms.band;

/**
 * One event alarm, ported from band.py (packets/Alarms.java).
 * Repetition mask bits, passed to the band unconverted:
 * Mon 0x01, Tue 0x02, Wed 0x04, Thu 0x08, Fri 0x10, Sat 0x20, Sun 0x40.
 * A mask of 0 is a one-time alarm.
 */
public final class EventAlarm {

    public static final int MON = 0x01;
    public static final int TUE = 0x02;
    public static final int WED = 0x04;
    public static final int THU = 0x08;
    public static final int FRI = 0x10;
    public static final int SAT = 0x20;
    public static final int SUN = 0x40;
    public static final int EVERY_DAY = 0x7F;

    /** Band slot, 1..5. */
    public int index;
    public boolean enabled;
    public int hour;
    public int minute;
    /** Repetition mask, see the bit constants above. */
    public int repeat;
    public String name = "Alarm";

    public EventAlarm(int index, boolean enabled, int hour, int minute,
                      int repeat, String name) {
        this.index = index;
        this.enabled = enabled;
        this.hour = hour;
        this.minute = minute;
        this.repeat = repeat;
        this.name = name;
    }

    public Tlv toTlv() {
        return new Tlv()
                .putByte(0x03, index)
                .putBool(0x04, enabled)
                .putShort(0x05, (hour << 8) | (minute & 0xFF))
                .putByte(0x06, repeat)
                .putString(0x07, name);
    }

    public static EventAlarm fromTlv(Tlv tlv) {
        int timeShort = tlv.getShort(0x05, 0);
        return new EventAlarm(
                tlv.getByte(0x03, 0),
                tlv.getBool(0x04, false),
                (timeShort >> 8) & 0xFF,
                timeShort & 0xFF,
                tlv.getByte(0x06, 0),
                tlv.getString(0x07, "Alarm"));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EventAlarm)) {
            return false;
        }
        EventAlarm o = (EventAlarm) other;
        return index == o.index && enabled == o.enabled && hour == o.hour
                && minute == o.minute && repeat == o.repeat
                && (name == null ? o.name == null : name.equals(o.name));
    }

    @Override
    public int hashCode() {
        int h = index * 31 + hour * 7 + minute;
        h = h * 31 + repeat;
        h = h * 31 + (enabled ? 1 : 0);
        return h * 31 + (name == null ? 0 : name.hashCode());
    }
}
