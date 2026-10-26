package app.oguri.alarms.band;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * TLV codec, ported from band.py (HuaweiTLV.java): tag is 1 byte, length
 * is a VarInt, values are big-endian. Repeated tags are allowed and order
 * is preserved. Pure Java, no Android dependencies.
 */
public final class Tlv {

    /** One tag/value pair. Value bytes are treated as immutable. */
    public static final class Item {
        public final int tag;
        public final byte[] value;

        Item(int tag, byte[] value) {
            this.tag = tag;
            this.value = value;
        }
    }

    private final List<Item> mItems = new ArrayList<Item>();

    public List<Item> items() {
        return mItems;
    }

    // ------------------------------------------------------------ builders

    public Tlv putRaw(int tag, byte[] value) {
        byte[] copy = new byte[value.length];
        System.arraycopy(value, 0, copy, 0, value.length);
        mItems.add(new Item(tag & 0xFF, copy));
        return this;
    }

    public Tlv putEmpty(int tag) {
        return putRaw(tag, new byte[0]);
    }

    public Tlv putByte(int tag, int value) {
        return putRaw(tag, new byte[]{(byte) (value & 0xFF)});
    }

    public Tlv putBool(int tag, boolean value) {
        return putRaw(tag, new byte[]{value ? (byte) 1 : (byte) 0});
    }

    public Tlv putShort(int tag, int value) {
        return putRaw(tag, new byte[]{
                (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)});
    }

    public Tlv putInt(int tag, int value) {
        return putRaw(tag, new byte[]{
                (byte) ((value >> 24) & 0xFF), (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)});
    }

    public Tlv putString(int tag, String value) {
        try {
            return putRaw(tag, value.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public Tlv putTlv(int tag, Tlv value) {
        return putRaw(tag, value.serialize());
    }

    // ------------------------------------------------------------ accessors

    public boolean contains(int tag) {
        for (Item item : mItems) {
            if (item.tag == tag) {
                return true;
            }
        }
        return false;
    }

    public byte[] getRaw(int tag) {
        for (Item item : mItems) {
            if (item.tag == tag) {
                return item.value;
            }
        }
        return null;
    }

    public List<byte[]> getAllRaw(int tag) {
        List<byte[]> out = new ArrayList<byte[]>();
        for (Item item : mItems) {
            if (item.tag == tag) {
                out.add(item.value);
            }
        }
        return out;
    }

    public int getByte(int tag, int defaultValue) {
        byte[] v = getRaw(tag);
        return (v != null && v.length > 0) ? (v[0] & 0xFF) : defaultValue;
    }

    public boolean getBool(int tag, boolean defaultValue) {
        byte[] v = getRaw(tag);
        return (v != null && v.length > 0) ? (v[0] != 0) : defaultValue;
    }

    public int getShort(int tag, int defaultValue) {
        byte[] v = getRaw(tag);
        if (v == null || v.length < 2) {
            return defaultValue;
        }
        return (short) (((v[0] & 0xFF) << 8) | (v[1] & 0xFF));
    }

    public int getInt(int tag, int defaultValue) {
        byte[] v = getRaw(tag);
        if (v == null || v.length < 4) {
            return defaultValue;
        }
        return ((v[0] & 0xFF) << 24) | ((v[1] & 0xFF) << 16)
                | ((v[2] & 0xFF) << 8) | (v[3] & 0xFF);
    }

    public String getString(int tag, String defaultValue) {
        byte[] v = getRaw(tag);
        if (v == null) {
            return defaultValue;
        }
        try {
            return new String(v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return defaultValue;
        }
    }

    public Tlv getTlv(int tag) {
        byte[] v = getRaw(tag);
        return v != null ? parse(v) : new Tlv();
    }

    public List<Tlv> getTlvs(int tag) {
        List<Tlv> out = new ArrayList<Tlv>();
        for (byte[] v : getAllRaw(tag)) {
            out.add(parse(v));
        }
        return out;
    }

    // ------------------------------------------------------- serialization

    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Item item : mItems) {
            out.write(item.tag);
            byte[] len = VarInt.encode(item.value.length);
            out.write(len, 0, len.length);
            out.write(item.value, 0, item.value.length);
        }
        return out.toByteArray();
    }

    public static Tlv parse(byte[] buf) {
        Tlv tlv = new Tlv();
        int parsed = 0;
        while (parsed < buf.length) {
            int tag = buf[parsed] & 0xFF;
            parsed++;
            if (parsed == buf.length && tag == 0) {
                break; // trailing zero padding (HuaweiTLV.parse)
            }
            VarInt.Decoded size = VarInt.decode(buf, parsed);
            parsed += size.bytesUsed;
            if (parsed + size.value > buf.length) {
                throw new IllegalArgumentException("TLV value overruns buffer");
            }
            byte[] value = new byte[size.value];
            System.arraycopy(buf, parsed, value, 0, size.value);
            tlv.mItems.add(new Item(tag, value));
            parsed += size.value;
        }
        return tlv;
    }
}
