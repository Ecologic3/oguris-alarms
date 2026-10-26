package app.oguri.alarms.band;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Packet framing, ported from band.py (HuaweiPacket.java).
 *
 * Unsliced frame: [0x5A][len u16 BE][0x00][service][command][TLV][CRC16 BE],
 * where len = 1 + 2 + len(TLV) and the CRC covers everything before it.
 * Sliced frames use markers 0x01 first, 0x02 middle, 0x03 last, plus a
 * per-frame counter byte; len = 2 + len(chunk).
 */
public final class HuaweiPacket {

    public static final int MAGIC = 0x5A;
    public static final int DEFAULT_SLICE_SIZE = 0xF4; // 244

    private HuaweiPacket() {
    }

    /** A completed, reassembled packet. */
    public static final class Packet {
        public final int service;
        public final int command;
        public final byte[] tlvBytes;

        Packet(int service, int command, byte[] tlvBytes) {
            this.service = service;
            this.command = command;
            this.tlvBytes = tlvBytes;
        }
    }

    public static List<byte[]> buildFrames(int service, int command, byte[] tlvBytes) {
        return buildFrames(service, command, tlvBytes, DEFAULT_SLICE_SIZE);
    }

    public static List<byte[]> buildFrames(int service, int command, byte[] tlvBytes,
                                           int sliceSize) {
        byte[] payload = new byte[2 + tlvBytes.length];
        payload[0] = (byte) (service & 0xFF);
        payload[1] = (byte) (command & 0xFF);
        System.arraycopy(tlvBytes, 0, payload, 2, tlvBytes.length);

        int maxBody = sliceSize - 5 - 2;
        int packetCount = (payload.length + maxBody - 1) / maxBody;
        List<byte[]> frames = new ArrayList<byte[]>();
        if (packetCount <= 1) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(MAGIC);
            writeShort(frame, payload.length + 1);
            frame.write(0x00);
            frame.write(payload, 0, payload.length);
            byte[] head = frame.toByteArray();
            writeShort(frame, Crc16.compute(head));
            frames.add(frame.toByteArray());
            return frames;
        }
        int offset = 0;
        int flag = 0;
        for (int i = 0; i < packetCount; i++) {
            boolean last = i == packetCount - 1;
            int remaining = payload.length - offset;
            int packetSize = Math.min(sliceSize, remaining + 5 + 2);
            int contentSize = packetSize - 5 - 2;
            int marker = last ? 0x03 : (i == 0 ? 0x01 : 0x02);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(MAGIC);
            writeShort(frame, packetSize - 5);
            frame.write(marker);
            frame.write(flag);
            flag++;
            frame.write(payload, offset, contentSize);
            byte[] head = frame.toByteArray();
            writeShort(frame, Crc16.compute(head));
            frames.add(frame.toByteArray());
            offset += contentSize;
        }
        return frames;
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * Incremental parser mirroring band.py FrameParser: tolerates
     * notifications that split or join frames, verifies the CRC, and
     * reassembles sliced payloads.
     */
    public static final class FrameParser {
        private final ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();
        private ByteArrayOutputStream mPartialPayload;

        public List<Packet> feed(byte[] data) {
            mBuffer.write(data, 0, data.length);
            List<Packet> out = new ArrayList<Packet>();
            while (true) {
                int[] markerHolder = new int[1];
                byte[] chunk = tryParseOne(markerHolder);
                if (chunk == null) {
                    break;
                }
                Packet packet = handlePayload(markerHolder[0], chunk);
                if (packet != null) {
                    out.add(packet);
                }
            }
            return out;
        }

        /** Returns the chunk, or null if more bytes are needed. */
        private byte[] tryParseOne(int[] markerHolder) {
            byte[] buf = mBuffer.toByteArray();
            if (buf.length < 3) {
                return null;
            }
            if ((buf[0] & 0xFF) != MAGIC) {
                throw new IllegalArgumentException("magic mismatch");
            }
            int length = ((buf[1] & 0xFF) << 8) | (buf[2] & 0xFF);
            int total = length + 3 + 2;
            if (buf.length < total) {
                return null;
            }
            byte[] frame = new byte[total];
            System.arraycopy(buf, 0, frame, 0, total);
            // Drop the consumed frame from the buffer.
            mBuffer.reset();
            if (buf.length > total) {
                mBuffer.write(buf, total, buf.length - total);
            }
            int expectedCrc = ((frame[total - 2] & 0xFF) << 8) | (frame[total - 1] & 0xFF);
            int actualCrc = Crc16.compute(frame, 0, total - 2, 0x0000);
            if (actualCrc != expectedCrc) {
                throw new IllegalArgumentException("CRC mismatch");
            }
            int marker = frame[3] & 0xFF;
            markerHolder[0] = marker;
            if (marker == 0x00) {
                return slice(frame, 4, total - 2);
            }
            if (marker == 1 || marker == 2 || marker == 3) {
                return slice(frame, 5, total - 2);
            }
            // Unmarked payload fallback, as in band.py.
            markerHolder[0] = 0;
            return slice(frame, 3, total - 2);
        }

        private Packet handlePayload(int marker, byte[] chunk) {
            byte[] payload;
            if (marker == 0) {
                payload = chunk;
            } else {
                if (mPartialPayload == null) {
                    mPartialPayload = new ByteArrayOutputStream();
                }
                mPartialPayload.write(chunk, 0, chunk.length);
                if (marker != 3) {
                    return null;
                }
                payload = mPartialPayload.toByteArray();
                mPartialPayload = null;
            }
            if (payload.length < 2) {
                return null;
            }
            byte[] tlv = new byte[payload.length - 2];
            System.arraycopy(payload, 2, tlv, 0, tlv.length);
            return new Packet(payload[0] & 0xFF, payload[1] & 0xFF, tlv);
        }

        private static byte[] slice(byte[] data, int from, int toExclusive) {
            byte[] out = new byte[toExclusive - from];
            System.arraycopy(data, from, out, 0, out.length);
            return out;
        }
    }
}
