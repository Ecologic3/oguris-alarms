package app.oguri.alarms;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import app.oguri.alarms.band.DeviceConfigPackets;
import app.oguri.alarms.band.HuaweiCrypto;
import app.oguri.alarms.band.HuaweiPacket;
import app.oguri.alarms.band.LinkParams;
import app.oguri.alarms.band.Tlv;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One direct BLE session with the Honor Band 4, a synchronous port of
 * the flow proven live by direct/prototype/band.py on 2026-10-26
 * (PROTOCOL.md section 7.3):
 *
 *   GATT connect (LE, no OS bonding) -> request a large MTU ->
 *   discover the FE86 service -> enable notifications on FE02 (CCCD) ->
 *   LinkParams -> Auth (v1) -> BondParams -> Bond (skipped only when
 *   BondParams reports status 1) -> ONE operation -> disconnect.
 *
 * Session methods block, so execute() must run on a worker thread
 * (DirectBandTransport owns one). Every step has a timeout and failures
 * raise BandException with a user-readable message. Requests are
 * strictly one-outstanding and matched by (service, command), exactly
 * like Gadgetbridge's request chain and band.py.
 *
 * Android framework APIs only; no AndroidX, no lambdas.
 */
public final class BandSession {

    private static final String TAG = "BandSession";

    /** MAC of the Honor Band 4. */
    public static final String BAND_MAC = "D0:16:B4:DC:87:10";

    private static final UUID SERVICE_UUID =
            UUID.fromString("0000fe86-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_UUID =
            UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb");
    private static final UUID READ_UUID =
            UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long CONNECT_TIMEOUT_MS = 20000;
    private static final long MTU_TIMEOUT_MS = 5000;
    private static final long DISCOVERY_TIMEOUT_MS = 15000;
    private static final long DESCRIPTOR_TIMEOUT_MS = 10000;
    private static final long WRITE_TIMEOUT_MS = 10000;
    private static final long RESPONSE_TIMEOUT_MS = 15000;
    private static final int REQUESTED_MTU = 517;

    /** The single band operation a session runs after the init sequence. */
    public interface Operation {
        void run(BandSession session) throws BandException;
    }

    private interface Waiter {
        boolean isDone();
    }

    private final Context mAppCtx;
    private final Object mLock = new Object();
    private final HuaweiPacket.FrameParser mParser = new HuaweiPacket.FrameParser();

    private BluetoothGatt mGatt;
    private BluetoothGattCharacteristic mWriteChar;
    private BluetoothGattCharacteristic mReadChar;

    // Callback state, guarded by mLock.
    private boolean mConnected;
    private boolean mDisconnected = true;
    private boolean mMtuDone;
    private int mServicesStatus = -1; // -1 pending, else GATT status
    private boolean mDescriptorDone;
    private int mDescriptorStatus;
    private boolean mWriteDone;
    private int mWriteStatus;
    private String mFatalError;

    // Pending request/response matching, guarded by mLock.
    private int mPendingService = -1;
    private int mPendingCommand = -1;
    private HuaweiPacket.Packet mPendingResult;

    // Protocol state for this session.
    private LinkParams mLink;
    private byte[] mSecretKey;
    private String mFakeMac;
    private HuaweiCrypto.IvGenerator mIvGen;

    private BandSession(Context ctx) {
        mAppCtx = ctx.getApplicationContext();
    }

    /**
     * Runs a whole session: connect, init handshake, the operation,
     * then disconnect (always, even on failure).
     */
    public static void execute(Context ctx, Operation operation) throws BandException {
        BandSession session = new BandSession(ctx);
        try {
            session.open();
            session.initialize();
            operation.run(session);
        } finally {
            session.close();
        }
    }

    // ------------------------------------------------------------ lifecycle

    private void open() throws BandException {
        BluetoothManager manager = (BluetoothManager)
                mAppCtx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter()
                : BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new BandException("Bluetooth is not available on this phone");
        }
        if (Build.VERSION.SDK_INT >= 31
                && mAppCtx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            throw new BandException("Bluetooth permission not granted "
                    + "(allow Nearby devices for Oguri's Alarms)");
        }
        if (Build.VERSION.SDK_INT <= 30
                && mAppCtx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new BandException("Location permission not granted "
                    + "(needed for Bluetooth on this Android version)");
        }
        if (!adapter.isEnabled()) {
            throw new BandException("Bluetooth is off");
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(BAND_MAC);
        } catch (IllegalArgumentException e) {
            throw new BandException("Bad band address " + BAND_MAC, e);
        }
        // Plain GATT connect, no auto-connect, no OS bonding: the same
        // style Gadgetbridge uses (its Huawei flow is BONDING_STYLE_NONE).
        // TRANSPORT_LE forces a BLE connection to this dual-mode stack.
        try {
            mGatt = device.connectGatt(mAppCtx, false, mCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            throw new BandException("Bluetooth permission was revoked", e);
        }
        if (mGatt == null) {
            throw new BandException("Could not start a Bluetooth connection");
        }
        await(new Waiter() {
            @Override
            public boolean isDone() {
                return mConnected || mFatalError != null;
            }
        }, CONNECT_TIMEOUT_MS, "connecting to the band");
        if (mFatalError != null) {
            throw new BandException(mFatalError);
        }

        // A large MTU matters: protocol frames are up to sliceSize bytes
        // and ATT values cannot exceed MTU-3. Failure is non-fatal; the
        // band clamps to its own maximum and small frames still fit.
        try {
            if (mGatt.requestMtu(REQUESTED_MTU)) {
                await(new Waiter() {
                    @Override
                    public boolean isDone() {
                        return mMtuDone || mFatalError != null;
                    }
                }, MTU_TIMEOUT_MS, "MTU negotiation");
            }
        } catch (Exception e) {
            Log.w(TAG, "requestMtu failed, continuing with default MTU", e);
        }
        if (mFatalError != null) {
            throw new BandException(mFatalError);
        }

        if (!mGatt.discoverServices()) {
            throw new BandException("Could not start service discovery");
        }
        await(new Waiter() {
            @Override
            public boolean isDone() {
                return mServicesStatus != -1 || mFatalError != null;
            }
        }, DISCOVERY_TIMEOUT_MS, "service discovery");
        if (mFatalError != null) {
            throw new BandException(mFatalError);
        }
        if (mServicesStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new BandException("Service discovery failed (status "
                    + mServicesStatus + ")");
        }
        BluetoothGattService service = mGatt.getService(SERVICE_UUID);
        if (service == null) {
            throw new BandException("Band service FE86 not found "
                    + "(is this the Honor Band 4?)");
        }
        mWriteChar = service.getCharacteristic(WRITE_UUID);
        mReadChar = service.getCharacteristic(READ_UUID);
        if (mWriteChar == null || mReadChar == null) {
            throw new BandException("Band FE01/FE02 characteristics not found");
        }
        enableNotifications();
    }

    private void enableNotifications() throws BandException {
        if (!mGatt.setCharacteristicNotification(mReadChar, true)) {
            throw new BandException("Could not enable band notifications");
        }
        BluetoothGattDescriptor cccd = mReadChar.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            throw new BandException("Band notification descriptor (CCCD) missing");
        }
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!mGatt.writeDescriptor(cccd)) {
            throw new BandException("Could not write the notification descriptor");
        }
        await(new Waiter() {
            @Override
            public boolean isDone() {
                return mDescriptorDone || mFatalError != null;
            }
        }, DESCRIPTOR_TIMEOUT_MS, "enabling notifications");
        if (mFatalError != null) {
            throw new BandException(mFatalError);
        }
        if (mDescriptorStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new BandException("Enabling notifications failed (status "
                    + mDescriptorStatus + ")");
        }
    }

    private void close() {
        if (mGatt == null) {
            return;
        }
        try {
            mGatt.disconnect();
            // Give the stack a moment to send the disconnect before the
            // GATT handle is closed; close() right away can drop it.
            synchronized (mLock) {
                long deadline = System.currentTimeMillis() + 2000;
                while (!mDisconnected) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        mLock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "disconnect failed", e);
        } finally {
            try {
                mGatt.close();
            } catch (Exception ignored) {
                // Closing a dead GATT handle must never throw upward.
            }
            mGatt = null;
        }
    }

    // -------------------------------------------------------- init sequence

    private void initialize() throws BandException {
        // LinkParams, plaintext, at the default slice size (band.py and
        // Gadgetbridge both send it before the band's size is known).
        Tlv resp = request(DeviceConfigPackets.SVC,
                DeviceConfigPackets.CMD_LINK_PARAMS,
                DeviceConfigPackets.buildLinkParamsRequest(), false);
        mLink = LinkParams.parse(resp);
        if (mLink.deviceSupportType != 0) {
            // The HiChain branch is not implemented (and this band has
            // never reported it). Fail loudly rather than guessing.
            throw new BandException("Band reported unsupported auth mode "
                    + mLink.deviceSupportType + " (expected 0)");
        }

        // Our identity for this band: persisted secret key + fake MAC,
        // exactly as band.py's state file and Gadgetbridge keep them.
        mSecretKey = DirectBandState.secretKey(mAppCtx);
        mFakeMac = DirectBandState.fakeMac(mAppCtx);

        // Auth, plaintext: challenge/response over baked-in constants.
        byte[] clientNonce = new byte[16];
        new SecureRandom().nextBytes(clientNonce);
        DeviceConfigPackets.AuthExchange auth = DeviceConfigPackets.buildAuthRequest(
                mLink.authVersion, mLink.serverNonce, clientNonce);
        Tlv authResp = request(DeviceConfigPackets.SVC,
                DeviceConfigPackets.CMD_AUTH, auth.request, false);
        byte[] answer = authResp.getRaw(0x01);
        if (answer == null || !Arrays.equals(answer, auth.expectedResponse)) {
            throw new BandException("Band rejected authentication "
                    + "(challenge response mismatch)");
        }

        // BondParams, plaintext: tells us whether the band already holds
        // a bond, and seeds the IV counter.
        Tlv bondParamsResp = request(DeviceConfigPackets.SVC,
                DeviceConfigPackets.CMD_BOND_PARAMS,
                DeviceConfigPackets.buildBondParamsRequest(mFakeMac), false);
        DeviceConfigPackets.BondParamsResult bondParams =
                DeviceConfigPackets.parseBondParamsResponse(bondParamsResp);
        mIvGen = new HuaweiCrypto.IvGenerator(bondParams.encryptionCounter);

        if (bondParams.status != 1) {
            // Bond: deliver our secret key, wrapped under the key derived
            // from the band's own MAC. Skipped only when the band says
            // it is already bonded (status 1), as in band.py / GB.
            byte[] iv = mIvGen.nextIv();
            Tlv bond = DeviceConfigPackets.buildBondRequest(
                    mLink.authVersion, BAND_MAC, mFakeMac, mSecretKey,
                    mLink.useGcm(), iv);
            request(DeviceConfigPackets.SVC, DeviceConfigPackets.CMD_BOND,
                    bond, false);
        }
        persistCounter();
    }

    private void persistCounter() {
        if (mIvGen != null) {
            DirectBandState.saveIvCounter(mAppCtx, mIvGen.counter());
        }
    }

    /**
     * Best-effort battery level read (BatteryLevel, 0x01/0x08), to run
     * inside an established session after bonding. Returns the level
     * in percent, or null ("unknown") on any failure: the battery is
     * cosmetic, so a battery problem must never fail the session's
     * real operation and every exception is swallowed here.
     */
    public Integer readBatteryLevel() {
        try {
            Tlv resp = request(DeviceConfigPackets.SVC,
                    DeviceConfigPackets.CMD_BATTERY,
                    DeviceConfigPackets.buildBatteryLevelRequest(), true);
            return DeviceConfigPackets.parseBatteryLevelResponse(resp);
        } catch (Exception e) {
            Log.w(TAG, "Battery level read failed, continuing without it", e);
            return null;
        }
    }

    // -------------------------------------------------------------- requests

    /**
     * Sends one request and waits for the response with the same
     * (service, command). Encrypted requests are wrapped with the
     * session's secret key and IV counter, as in band.py. Callers run
     * inside Operation.run, i.e. after a successful init, except for the
     * init sequence itself (plaintext).
     */
    public Tlv request(int service, int command, Tlv tlv, boolean encrypted)
            throws BandException {
        byte[] body = tlv.serialize();
        if (encrypted) {
            if (mIvGen == null || mSecretKey == null || mLink == null) {
                throw new BandException("Encrypted request before bonding");
            }
            body = HuaweiCrypto.encryptTlv(mLink.useGcm(), mSecretKey,
                    mIvGen.nextIv(), body);
            persistCounter();
        }
        int sliceSize = mLink != null ? mLink.sliceSize
                : HuaweiPacket.DEFAULT_SLICE_SIZE;
        List<byte[]> frames = HuaweiPacket.buildFrames(service, command, body, sliceSize);

        synchronized (mLock) {
            mPendingService = service;
            mPendingCommand = command;
            mPendingResult = null;
        }
        try {
            for (byte[] frame : frames) {
                writeFrame(frame);
            }
            await(new Waiter() {
                @Override
                public boolean isDone() {
                    return mPendingResult != null || mFatalError != null;
                }
            }, RESPONSE_TIMEOUT_MS,
                    "response to service " + service + " command " + command);
        } finally {
            synchronized (mLock) {
                mPendingService = -1;
                mPendingCommand = -1;
            }
        }
        HuaweiPacket.Packet packet;
        synchronized (mLock) {
            packet = mPendingResult;
            mPendingResult = null;
        }
        if (packet == null) {
            // A result that arrived just before a link error still counts
            // as a completed request; only a missing result is a failure.
            if (mFatalError != null) {
                throw new BandException(mFatalError);
            }
            throw new BandException("No response from the band");
        }
        Tlv parsed = Tlv.parse(packet.tlvBytes);
        if (encrypted) {
            try {
                return HuaweiCrypto.decryptTlv(mLink.useGcm(), mSecretKey, parsed);
            } catch (RuntimeException e) {
                throw new BandException("Band reply could not be decrypted "
                        + "(bond key mismatch?)", e);
            }
        }
        return parsed;
    }

    private void writeFrame(byte[] frame) throws BandException {
        synchronized (mLock) {
            mWriteDone = false;
            mWriteStatus = 0;
        }
        mWriteChar.setValue(frame);
        mWriteChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        if (!mGatt.writeCharacteristic(mWriteChar)) {
            throw new BandException("Could not write to the band");
        }
        await(new Waiter() {
            @Override
            public boolean isDone() {
                return mWriteDone || mFatalError != null;
            }
        }, WRITE_TIMEOUT_MS, "writing to the band");
        if (mFatalError != null) {
            throw new BandException(mFatalError);
        }
        if (mWriteStatus != BluetoothGatt.GATT_SUCCESS) {
            throw new BandException("Writing to the band failed (status "
                    + mWriteStatus + ")");
        }
    }

    // ---------------------------------------------------------------- waits

    private void await(Waiter waiter, long timeoutMs, String what)
            throws BandException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (mLock) {
            while (!waiter.isDone()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new BandException("Timed out waiting for " + what);
                }
                try {
                    mLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BandException("Interrupted waiting for " + what);
                }
            }
        }
    }

    // -------------------------------------------------------------- callback

    private final BluetoothGattCallback mCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                            int newState) {
            synchronized (mLock) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    mConnected = true;
                    mDisconnected = false;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        mFatalError = "Connection failed (status " + status + ")";
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    mDisconnected = true;
                    if (mConnected && mFatalError == null) {
                        mFatalError = "Band disconnected unexpectedly";
                    } else if (mFatalError == null) {
                        mFatalError = "Could not connect to the band "
                                + "(is it nearby? status " + status + ")";
                    }
                }
                mLock.notifyAll();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            synchronized (mLock) {
                mMtuDone = true;
                mLock.notifyAll();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            synchronized (mLock) {
                mServicesStatus = status;
                mLock.notifyAll();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            synchronized (mLock) {
                mDescriptorDone = true;
                mDescriptorStatus = status;
                mLock.notifyAll();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            synchronized (mLock) {
                mWriteDone = true;
                mWriteStatus = status;
                mLock.notifyAll();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic) {
            // Pre-API 33 callback shape; the API 33+ override below
            // handles newer devices, this one the rest.
            handleNotification(characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            handleNotification(value);
        }
    };

    private void handleNotification(byte[] value) {
        if (value == null) {
            return;
        }
        List<HuaweiPacket.Packet> packets;
        try {
            packets = mParser.feed(value);
        } catch (RuntimeException e) {
            // A garbled frame must not kill the session thread; the
            // pending request will time out with a clear error instead.
            Log.w(TAG, "Ignoring unparseable band notification", e);
            return;
        }
        for (HuaweiPacket.Packet packet : packets) {
            synchronized (mLock) {
                if (packet.service == mPendingService
                        && packet.command == mPendingCommand) {
                    mPendingResult = packet;
                } else {
                    // Known unsolicited traffic (battery, band time
                    // requests) needs no answer on this band; the live
                    // session stayed connected without replying.
                    Log.d(TAG, "Unsolicited band packet service=" + packet.service
                            + " command=" + packet.command);
                }
                mLock.notifyAll();
            }
        }
    }
}
