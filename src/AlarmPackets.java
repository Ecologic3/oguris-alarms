package app.oguri.alarms.band;

import java.util.ArrayList;
import java.util.List;

/**
 * Alarm service packets (service 0x08), ported from band.py.
 * Setting event alarms replaces the whole list on the band; an empty list
 * is encoded as one dummy entry with only index 1, which disables all.
 *
 * TODO(live): on-device repetition semantics, name truncation and set
 * atomicity are PROTOCOL.md section 7.2 questions 4, 5 and 6; the encodings
 * here follow Gadgetbridge source only.
 */
public final class AlarmPackets {

    public static final int SVC = 0x08;
    public static final int CMD_SET_EVENT_ALARMS = 0x01;
    public static final int CMD_SET_SMART_ALARM = 0x02;
    public static final int CMD_GET_EVENT_ALARMS = 0x03;
    public static final int CMD_GET_SMART_ALARM = 0x04;

    private AlarmPackets() {
    }

    public static Tlv buildGetEventAlarmsRequest() {
        return new Tlv().putEmpty(0x01);
    }

    public static Tlv buildGetSmartAlarmRequest() {
        return new Tlv().putEmpty(0x01);
    }

    public static List<EventAlarm> parseEventAlarmList(Tlv resp) {
        List<EventAlarm> out = new ArrayList<EventAlarm>();
        Tlv container = resp.getTlv(0x81);
        for (Tlv entry : container.getTlvs(0x82)) {
            out.add(EventAlarm.fromTlv(entry));
        }
        return out;
    }

    /** Returns null when the response holds no smart alarm. */
    public static SmartAlarm parseSmartAlarm(Tlv resp) {
        Tlv container = resp.getTlv(0x81);
        if (container.contains(0x82)) {
            return SmartAlarm.fromTlv(container.getTlv(0x82));
        }
        return null;
    }

    public static Tlv buildSetEventAlarmsRequest(List<EventAlarm> alarms) {
        if (alarms.size() > 5) {
            throw new IllegalArgumentException("the band holds at most 5 event alarms");
        }
        Tlv inner = new Tlv();
        if (alarms.isEmpty()) {
            inner.putTlv(0x82, new Tlv().putByte(0x03, 0x01));
        } else {
            for (EventAlarm alarm : alarms) {
                inner.putTlv(0x82, alarm.toTlv());
            }
        }
        return new Tlv().putTlv(0x81, inner);
    }

    public static Tlv buildSetSmartAlarmRequest(SmartAlarm alarm) {
        return new Tlv().putTlv(0x81, new Tlv().putTlv(0x82, alarm.toTlv()));
    }
}
