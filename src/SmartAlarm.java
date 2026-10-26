package app.oguri.alarms.band;

/**
 * The single smart alarm, ported from band.py. It differs from an event
 * alarm only in tag 0x07: minutes the band may wake the wearer early
 * (Gadgetbridge default 5), instead of a name. Its index field is always 1.
 */
public final class SmartAlarm {

    public boolean enabled;
    public int hour;
    public int minute;
    public int repeat;
    public int aheadTime = 5;

    public SmartAlarm(boolean enabled, int hour, int minute, int repeat, int aheadTime) {
        this.enabled = enabled;
        this.hour = hour;
        this.minute = minute;
        this.repeat = repeat;
        this.aheadTime = aheadTime;
    }

    public Tlv toTlv() {
        return new Tlv()
                .putByte(0x03, 1)
                .putBool(0x04, enabled)
                .putShort(0x05, (hour << 8) | (minute & 0xFF))
                .putByte(0x06, repeat)
                .putByte(0x07, aheadTime);
    }

    public static SmartAlarm fromTlv(Tlv tlv) {
        int timeShort = tlv.getShort(0x05, 0);
        return new SmartAlarm(
                tlv.getBool(0x04, false),
                (timeShort >> 8) & 0xFF,
                timeShort & 0xFF,
                tlv.getByte(0x06, 0),
                tlv.getByte(0x07, 0));
    }
}
