package app.oguri.alarms;

import android.content.Context;
import android.content.SharedPreferences;

import app.oguri.alarms.band.DeviceConfigPackets;

import java.security.SecureRandom;

/**
 * Persisted direct-protocol state, the Android equivalent of band.py's
 * band_state.json. Exactly like Gadgetbridge's per-device state, this is:
 * - the 16-byte secret key this host invented and delivered to the band
 *   in the Bond packet (generated once, then kept forever; the band
 *   encrypts traffic with whichever key was Bonded last);
 * - the fake client MAC (FF:FF:FF:xx:xx:xx) Gadgetbridge-style hosts
 *   present in BondParams instead of the phone's real Bluetooth MAC;
 * - the last IV counter used, as a diagnostic. The authoritative seed
 *   for each session still comes from that session's BondParams reply,
 *   exactly as in band.py.
 *
 * Stored in the app's private SharedPreferences; never leaves the app.
 */
public final class DirectBandState {

    private static final String PREFS = "direct_band_state";
    private static final String KEY_SECRET = "secret_key_hex";
    private static final String KEY_FAKE_MAC = "fake_mac";
    private static final String KEY_IV_COUNTER = "iv_counter";

    private DirectBandState() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The persisted secret key, generated (and stored) on first use. */
    public static synchronized byte[] secretKey(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        String hex = prefs.getString(KEY_SECRET, null);
        if (hex != null && hex.length() == 32) {
            byte[] key = parseHex(hex);
            if (key != null) {
                return key;
            }
        }
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        prefs.edit().putString(KEY_SECRET, toHex(key)).apply();
        return key;
    }

    /** The persisted fake MAC, generated (and stored) on first use. */
    public static synchronized String fakeMac(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        String mac = prefs.getString(KEY_FAKE_MAC, null);
        if (mac != null && mac.length() == 17) {
            return mac;
        }
        mac = DeviceConfigPackets.createRandomFakeMac(new SecureRandom());
        prefs.edit().putString(KEY_FAKE_MAC, mac).apply();
        return mac;
    }

    public static synchronized long ivCounter(Context ctx) {
        return prefs(ctx).getLong(KEY_IV_COUNTER, 0L);
    }

    public static synchronized void saveIvCounter(Context ctx, long counter) {
        prefs(ctx).edit().putLong(KEY_IV_COUNTER, counter & 0xFFFFFFFFL).apply();
    }

    // ------------------------------------------------------------- hex utils

    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            String s = Integer.toHexString(b & 0xFF);
            if (s.length() == 1) {
                sb.append('0');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static byte[] parseHex(String hex) {
        try {
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
