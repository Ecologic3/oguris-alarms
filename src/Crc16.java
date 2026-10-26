package app.oguri.alarms.band;

/**
 * CRC16, a verbatim port of Gadgetbridge CheckSums.getCRC16 as ported in
 * direct/prototype/band.py. Huawei packets call it with init 0x0000.
 * Pure Java, no Android or third party dependencies.
 */
public final class Crc16 {

    private Crc16() {
    }

    public static int compute(byte[] data) {
        return compute(data, 0, data.length, 0x0000);
    }

    public static int compute(byte[] data, int offset, int length, int crc) {
        for (int i = offset; i < offset + length; i++) {
            int b = data[i] & 0xFF;
            crc = ((crc >> 8) | (crc << 8)) & 0xFFFF;
            crc ^= b;
            crc ^= (crc & 0xFF) >> 4;
            crc ^= (crc << 12) & 0xFFFF;
            crc ^= ((crc & 0xFF) << 5) & 0xFFFF;
        }
        return crc & 0xFFFF;
    }
}
