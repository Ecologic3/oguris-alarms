package app.oguri.alarms.band;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Device config packet builders (service 0x01), ported from band.py:
 * LinkParams, Auth, BondParams, Bond, SetTime, SupportedServices and
 * SupportedCommands payload construction and parsing.
 *
 * TODO(live): only the normal auth mode is implemented here. If the real
 * band reports a HiChain deviceSupportType (1, 2, 3 or 4), the init flow
 * needs the HiChain branch, PROTOCOL.md section 7.2 question 2.
 * TODO(live): Bond takeover is unresolved, PROTOCOL.md section 7.2
 * question 1. If BondParams status is 1 because the band is bonded to a
 * different host key, skipping Bond (as Gadgetbridge does) leaves this
 * host encrypting with a key the band does not hold.
 */
public final class DeviceConfigPackets {

    public static final int SVC = 0x01;
    public static final int CMD_LINK_PARAMS = 0x01;
    public static final int CMD_SUPPORTED_SERVICES = 0x02;
    public static final int CMD_SUPPORTED_COMMANDS = 0x03;
    public static final int CMD_TIME = 0x05;
    public static final int CMD_PRODUCT_INFO = 0x07;
    public static final int CMD_BATTERY = 0x08;
    /** Unsolicited battery update; same response format as CMD_BATTERY. */
    public static final int CMD_BATTERY_CHANGE = 0x27;
    public static final int CMD_BOND = 0x0E;
    public static final int CMD_BOND_PARAMS = 0x0F;
    public static final int CMD_AUTH = 0x13;

    /** Services Gadgetbridge tells the band it supports (band.py list). */
    public static final byte[] KNOWN_SUPPORTED_SERVICES = new byte[]{
            0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A,
            0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14,
            0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1D, 0x20,
            0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x2A, 0x2B, 0x2D, 0x2E,
            0x30, 0x32, 0x33, 0x34, 0x35,
    };

    /** Commands advertised per service; order matters for the bitmap. */
    public static Map<Integer, byte[]> advertisedCommands() {
        Map<Integer, byte[]> map = new LinkedHashMap<Integer, byte[]>();
        map.put(0x01, new byte[]{
                0x04, 0x07, 0x08, 0x09, 0x0A, 0x0D, 0x0E, 0x10, 0x11, 0x12,
                0x13, 0x14, 0x1B, 0x1A, 0x1D, 0x21, 0x22, 0x23, 0x24, 0x29,
                0x2A, 0x2B, 0x32, 0x2E, 0x31, 0x30, 0x35, 0x36, 0x37, 0x2F});
        map.put(0x08, new byte[]{0x01, 0x02, 0x03});
        return map;
    }

    private DeviceConfigPackets() {
    }

    // ------------------------------------------------------------ LinkParams

    public static Tlv buildLinkParamsRequest() {
        return new Tlv().putEmpty(0x01).putEmpty(0x02).putEmpty(0x03).putEmpty(0x04);
    }

    /** The complete LinkParams request frame (the selftest vector). */
    public static byte[] buildLinkParamsFrame() {
        return HuaweiPacket.buildFrames(SVC, CMD_LINK_PARAMS,
                buildLinkParamsRequest().serialize()).get(0);
    }

    // ------------------------------------------------------------------ Auth

    /** Result of building an Auth request: the TLV plus the expected reply. */
    public static final class AuthExchange {
        public final Tlv request;
        /** First 32 bytes the band must answer with in its Auth response. */
        public final byte[] expectedResponse;

        AuthExchange(Tlv request, byte[] expectedResponse) {
            this.request = request;
            this.expectedResponse = expectedResponse;
        }
    }

    public static AuthExchange buildAuthRequest(int authVersion, byte[] serverNonce,
                                                byte[] clientNonce) {
        byte[] doubleNonce = HuaweiCrypto.concat(serverNonce, clientNonce);
        byte[] digest = HuaweiCrypto.digestChallenge(authVersion, doubleNonce);
        byte[] challenge = new byte[32];
        System.arraycopy(digest, 0, challenge, 0, 32);
        byte[] nonceField = new byte[2 + clientNonce.length];
        nonceField[0] = (byte) ((authVersion >> 8) & 0xFF);
        nonceField[1] = (byte) (authVersion & 0xFF);
        System.arraycopy(clientNonce, 0, nonceField, 2, clientNonce.length);
        Tlv request = new Tlv().putRaw(0x01, challenge).putRaw(0x02, nonceField);
        byte[] expected = new byte[32];
        System.arraycopy(HuaweiCrypto.digestResponse(authVersion, doubleNonce),
                0, expected, 0, 32);
        return new AuthExchange(request, expected);
    }

    // ------------------------------------------------------------------ Bond

    /** The fake client MAC Gadgetbridge persists: FF:FF:FF:xx:xx:xx. */
    public static String createRandomFakeMac(java.util.Random random) {
        StringBuilder sb = new StringBuilder("FF:FF:FF:");
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                sb.append(':');
            }
            String hex = Integer.toHexString(random.nextInt(256)).toUpperCase();
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /** Client serial: last 6 hex chars of the fake MAC, as ASCII bytes. */
    public static byte[] clientSerial(String fakeMac) {
        return HuaweiCrypto.utf8(fakeMac.replace(":", "").substring(6, 12));
    }

    public static Tlv buildBondParamsRequest(String fakeMac) {
        return new Tlv()
                .putEmpty(0x01)
                .putRaw(0x03, clientSerial(fakeMac))
                .putByte(0x04, 0x02)
                .putEmpty(0x05)
                .putRaw(0x07, HuaweiCrypto.utf8(fakeMac))
                .putEmpty(0x09);
    }

    /** BondParams response values the init flow needs. */
    public static final class BondParamsResult {
        public int status;
        public long encryptionCounter;
    }

    public static BondParamsResult parseBondParamsResponse(Tlv resp) {
        BondParamsResult out = new BondParamsResult();
        out.status = resp.getByte(0x01, 0);
        out.encryptionCounter = resp.getInt(0x09, 0) & 0xFFFFFFFFL;
        return out;
    }

    public static Tlv buildBondRequest(int authVersion, String bandMac, String fakeMac,
                                       byte[] secretKey, boolean useGcm, byte[] iv) {
        byte[] wrappingKey = HuaweiCrypto.createSecretKeyFromMac(authVersion, bandMac);
        byte[] wrapped = HuaweiCrypto.aesEncrypt(useGcm, wrappingKey, iv, secretKey);
        return new Tlv()
                .putEmpty(0x01)
                .putByte(0x03, 0x00)
                .putRaw(0x05, clientSerial(fakeMac))
                .putRaw(0x06, wrapped)
                .putRaw(0x07, iv);
    }

    // ------------------------------------------------------------------ Time

    /**
     * First 6 bytes of HuaweiUtil.getTimeAndZoneId, as in band.py:
     * epoch int32 BE, then the offset hour byte (negative offsets are
     * encoded as magnitude + 128), then the offset minutes byte. Java
     * integer division truncates toward zero, matching the original.
     */
    public static byte[] timeAndZonePrefix(long epochSeconds, int offsetSeconds) {
        int hourByte;
        if (offsetSeconds < 0) {
            hourByte = (-offsetSeconds / 3600) + 128;
        } else {
            hourByte = offsetSeconds / 3600;
        }
        int minuteByte = (offsetSeconds / 60) % 60;
        return new byte[]{
                (byte) ((epochSeconds >> 24) & 0xFF),
                (byte) ((epochSeconds >> 16) & 0xFF),
                (byte) ((epochSeconds >> 8) & 0xFF),
                (byte) (epochSeconds & 0xFF),
                (byte) (hourByte & 0xFF),
                (byte) (minuteByte & 0xFF)};
    }

    public static Tlv buildSetTimeRequest(long epochSeconds, int offsetSeconds) {
        byte[] prefix = timeAndZonePrefix(epochSeconds, offsetSeconds);
        int zoneShort = ((prefix[4] & 0xFF) << 8) | (prefix[5] & 0xFF);
        // Tag 0x02 is an int16 in the packet; values with the negative
        // offset marker (hour byte >= 128) are sent as the raw 2 bytes,
        // which is exactly what the short cast preserves bit for bit.
        return new Tlv().putInt(0x01, (int) epochSeconds).putShort(0x02, (short) zoneShort);
    }

    // ---------------------------------------------------------------- Battery

    /** BatteryLevel request (DeviceConfig.BatteryLevel.Request): tag 0x01, empty. */
    public static Tlv buildBatteryLevelRequest() {
        return new Tlv().putEmpty(0x01);
    }

    /**
     * Parses a BatteryLevel response exactly as Gadgetbridge's
     * DeviceConfig.BatteryLevel.Response.parseTlv plus
     * GetBatteryLevelRequest.processResponse do:
     *   tag 0x01: the level in percent (single byte);
     *   tag 0x02: optional multi-battery level array (one byte each);
     *   tag 0x03: optional matching status array (unused here).
     * When tag 0x02 is present Gadgetbridge uses the multi-battery
     * values, so the first byte of tag 0x02 wins; otherwise the level
     * is the byte in tag 0x01. The prototype (band.py) looked only for
     * tag 0x02, which is why the live run read this band's response as
     * empty: on a single-battery band tag 0x02 is absent and the level
     * lives in tag 0x01. The same format also arrives unsolicited as
     * command 0x27 (BatteryLevel.id_change). Returns the level in
     * percent, or null when the response carries no level at all.
     */
    public static Integer parseBatteryLevelResponse(Tlv resp) {
        byte[] multi = resp.getRaw(0x02);
        if (multi != null && multi.length > 0) {
            return Integer.valueOf(multi[0] & 0xFF);
        }
        byte[] single = resp.getRaw(0x01);
        if (single != null && single.length > 0) {
            return Integer.valueOf(single[0] & 0xFF);
        }
        return null;
    }

    // ---------------------------------------------- Supported services/commands

    public static Tlv buildSupportedServicesRequest() {
        return new Tlv().putRaw(0x01, KNOWN_SUPPORTED_SERVICES);
    }

    public static Tlv buildSupportedCommandsRequest() {
        Tlv inner = new Tlv();
        for (Map.Entry<Integer, byte[]> e : advertisedCommands().entrySet()) {
            inner.putByte(0x02, e.getKey()).putRaw(0x03, e.getValue());
        }
        return new Tlv().putTlv(0x81, inner);
    }

    /**
     * Parses the SupportedCommands bitmap reply into per-service lists of
     * supported command IDs, as in band.py.
     */
    public static Map<Integer, java.util.List<Integer>> parseSupportedCommands(Tlv resp) {
        Map<Integer, java.util.List<Integer>> result =
                new LinkedHashMap<Integer, java.util.List<Integer>>();
        Map<Integer, byte[]> advertised = advertisedCommands();
        Tlv container = resp.getTlv(0x81);
        Integer current = null;
        for (Tlv.Item item : container.items()) {
            if (item.tag == 0x02 && item.value.length > 0) {
                current = Integer.valueOf(item.value[0] & 0xFF);
            } else if (item.tag == 0x04 && current != null) {
                byte[] adv = advertised.get(current);
                java.util.List<Integer> supported = new java.util.ArrayList<Integer>();
                if (adv != null) {
                    for (int i = 0; i < item.value.length && i < adv.length; i++) {
                        if (item.value[i] == 1) {
                            supported.add(Integer.valueOf(adv[i] & 0xFF));
                        }
                    }
                }
                result.put(current, supported);
            }
        }
        return result;
    }
}
