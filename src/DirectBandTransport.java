package app.oguri.alarms;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import app.oguri.alarms.band.AlarmPackets;
import app.oguri.alarms.band.DeviceConfigPackets;
import app.oguri.alarms.band.EventAlarm;
import app.oguri.alarms.band.HuaweiPacket;
import app.oguri.alarms.band.Tlv;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The direct BLE transport: the active BandTransport since the live
 * proof session of 2026-10-26 (PROTOCOL.md section 7.3).
 *
 * Each call runs one BandSession on a single background thread: connect
 * to the band, LinkParams/Auth/BondParams/Bond, then the one operation,
 * then disconnect. The single-thread executor keeps the app's call
 * order (for example dismiss-then-set during a rename) intact, and a
 * session never runs on the UI thread. Failures surface as an error
 * toast through the app's existing toast pattern; there is NO silent
 * fallback to the Gadgetbridge transport.
 *
 * The band's alarm list cannot be read back on this firmware (service
 * 0x08 supports only the two set commands), so setAllAlarms sends the
 * app's stored list as the whole replacement list, exactly like
 * Gadgetbridge does with its own copy.
 */
public final class DirectBandTransport implements BandTransport {

    private static final String TAG = "DirectBandTransport";

    /** MAC of the Honor Band 4. */
    public static final String BAND_MAC = BandSession.BAND_MAC;

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "oguri-band-session");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private List<byte[]> mLastBuiltFrames = new ArrayList<byte[]>();

    @Override
    public String name() {
        return "direct";
    }

    /**
     * Plaintext payload frames built by the most recent call (before
     * session encryption), for offline inspection.
     */
    public synchronized List<byte[]> lastBuiltFrames() {
        return mLastBuiltFrames;
    }

    @Override
    public void setAllAlarms(Context ctx, final List<EventAlarm> alarms) {
        final List<EventAlarm> copy = new ArrayList<EventAlarm>(alarms);
        Tlv request = AlarmPackets.buildSetEventAlarmsRequest(copy);
        synchronized (this) {
            mLastBuiltFrames = HuaweiPacket.buildFrames(
                    AlarmPackets.SVC, AlarmPackets.CMD_SET_EVENT_ALARMS,
                    request.serialize());
        }
        final Context appCtx = ctx.getApplicationContext();
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    BandSession.execute(appCtx, new BandSession.Operation() {
                        @Override
                        public void run(BandSession session) throws BandException {
                            session.request(AlarmPackets.SVC,
                                    AlarmPackets.CMD_SET_EVENT_ALARMS,
                                    AlarmPackets.buildSetEventAlarmsRequest(copy),
                                    true);
                            updateBatteryLevel(session, appCtx);
                        }
                    });
                } catch (BandException e) {
                    reportFailure(appCtx, e);
                } catch (RuntimeException e) {
                    reportFailure(appCtx, e);
                }
            }
        });
    }

    @Override
    public void dismissAllAlarms(Context ctx) {
        // On the direct protocol, clearing all event alarms is the same
        // set command with an empty list (one dummy index 1 entry).
        setAllAlarms(ctx, new ArrayList<EventAlarm>());
    }

    @Override
    public void setTime(Context ctx, final long epochMillis, final int offsetSeconds) {
        Tlv request = DeviceConfigPackets.buildSetTimeRequest(
                epochMillis / 1000L, offsetSeconds);
        synchronized (this) {
            mLastBuiltFrames = HuaweiPacket.buildFrames(
                    DeviceConfigPackets.SVC, DeviceConfigPackets.CMD_TIME,
                    request.serialize());
        }
        final Context appCtx = ctx.getApplicationContext();
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    BandSession.execute(appCtx, new BandSession.Operation() {
                        @Override
                        public void run(BandSession session) throws BandException {
                            long epochSeconds = epochMillis / 1000L;
                            Tlv tlv = DeviceConfigPackets.buildSetTimeRequest(
                                    epochSeconds, offsetSeconds);
                            Tlv resp = session.request(DeviceConfigPackets.SVC,
                                    DeviceConfigPackets.CMD_TIME, tlv, true);
                            // Mirror Gadgetbridge and band.py: if the band
                            // reports a different epoch, re-send once.
                            int deviceEpoch = resp.getInt(0x01, (int) epochSeconds);
                            if (deviceEpoch != (int) epochSeconds) {
                                session.request(DeviceConfigPackets.SVC,
                                        DeviceConfigPackets.CMD_TIME, tlv, true);
                            }
                            updateBatteryLevel(session, appCtx);
                        }
                    });
                } catch (BandException e) {
                    reportFailure(appCtx, e);
                } catch (RuntimeException e) {
                    reportFailure(appCtx, e);
                }
            }
        });
    }

    /**
     * Reads the band's battery level on the just-finished session and
     * stores it with its read time for the UI's band status row. The
     * read is best-effort (BandSession.readBatteryLevel never throws
     * and returns null on failure); a miss simply leaves the previous
     * stored level in place.
     */
    private static void updateBatteryLevel(BandSession session, Context appCtx) {
        Integer level = session.readBatteryLevel();
        if (level != null) {
            AlarmStore.setBandBatteryLevel(appCtx, level.intValue(),
                    System.currentTimeMillis());
        }
    }

    /**
     * Surfaces a failed session the way the rest of the app surfaces
     * problems: an error toast (plus a log line). Never silent, and
     * never a fallback to another transport.
     */
    private static void reportFailure(final Context appCtx, Exception e) {
        Log.e(TAG, "Band session failed", e);
        final String message = "Band sync failed: " + e.getMessage();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show();
            }
        });
    }
}
