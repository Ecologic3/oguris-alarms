package app.oguri.alarms.band;

/**
 * Parsed LinkParams response, ported from band.py.
 *
 * TODO(live): the values for the target band and firmware (especially
 * deviceSupportType, authVersion and encryptMethod) are only known after
 * a live LinkParams exchange, PROTOCOL.md section 7.2 questions 1 and 2.
 */
public final class LinkParams {

    public int protocolVersion;
    public int sliceSize = HuaweiPacket.DEFAULT_SLICE_SIZE;
    public int mtu = 0x14;
    public int interval;
    public int authVersion;
    public byte[] serverNonce = new byte[16];
    /** Auth mode selector: 0 = normal, 2 = HiChainLite, 1/3/4 = HiChain. */
    public int deviceSupportType;
    public int authAlgo;
    public int bondState;
    /** 0x01 = AES-GCM, otherwise AES-CBC. */
    public int encryptMethod;

    public static LinkParams parse(Tlv resp) {
        LinkParams lp = new LinkParams();
        lp.protocolVersion = resp.getByte(0x01, 0);
        lp.sliceSize = resp.getShort(0x02, HuaweiPacket.DEFAULT_SLICE_SIZE);
        lp.mtu = resp.getShort(0x03, 0x14);
        lp.interval = resp.getShort(0x04, 0);
        byte[] blob = resp.getRaw(0x05);
        if (blob != null && blob.length >= 18) {
            lp.authVersion = blob[1] & 0xFF;
            byte[] nonce = new byte[16];
            System.arraycopy(blob, 2, nonce, 0, 16);
            lp.serverNonce = nonce;
        }
        lp.deviceSupportType = resp.getByte(0x07, 0);
        lp.authAlgo = resp.getByte(0x08, 0);
        lp.bondState = resp.getByte(0x09, 0);
        lp.encryptMethod = resp.getByte(0x0C, 0);
        return lp;
    }

    public boolean useGcm() {
        return encryptMethod == 0x01;
    }
}
