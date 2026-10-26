package app.oguri.alarms.band;

import java.io.ByteArrayOutputStream;

/**
 * The TLV length encoding from HuaweiTLV (the VarInt class in band.py):
 * big-endian base-128, 7 payload bits per byte, 0x80 continuation bit on
 * all but the last byte. Values below 128 are a single byte.
 */
public final class VarInt {

    private VarInt() {
    }

    /** Result of a decode: the value plus how many bytes it occupied. */
    public static final class Decoded {
        public final int value;
        public final int bytesUsed;

        Decoded(int value, int bytesUsed) {
            this.value = value;
            this.bytesUsed = bytesUsed;
        }
    }

    public static byte[] encode(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("varint cannot be negative");
        }
        int[] groups = new int[5];
        int count = 0;
        int v = value;
        while (true) {
            groups[count++] = v & 0x7F;
            v >>= 7;
            if (v == 0) {
                break;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(count);
        for (int i = count - 1; i >= 0; i--) {
            int g = groups[i];
            if (i != 0) {
                g |= 0x80;
            }
            out.write(g);
        }
        return out.toByteArray();
    }

    public static Decoded decode(byte[] buf, int offset) {
        int result = 0;
        int consumed = 0;
        while (true) {
            int b = buf[offset + consumed] & 0xFF;
            result += b & 0x7F;
            consumed++;
            if ((b & 0x80) == 0) {
                break;
            }
            result <<= 7;
        }
        return new Decoded(result, consumed);
    }
}
