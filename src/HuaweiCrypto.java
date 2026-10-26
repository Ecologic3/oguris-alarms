package app.oguri.alarms.band;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crypto layer, ported from band.py (HuaweiCrypto.java, CryptoUtils.java).
 * Uses only javax.crypto / java.security, which exist both on Android and
 * on a desktop JVM, so the offline test harness can exercise this code.
 *
 * TODO(live): this implements the normal-mode constants only. The HiChain
 * branch (PROTOCOL.md section 7.2, questions 1 and 2) may need different
 * key material; that can only be settled against the real band.
 */
public final class HuaweiCrypto {

    public static final int TAG_ENCRYPTED_FLAG = 0x7C;
    public static final int TAG_IV = 0x7D;
    public static final int TAG_CIPHERTEXT = 0x7E;

    private static final byte[] SECRET_KEY_1_V1 = hex("6f756a796d777134636c763933373879");
    private static final byte[] SECRET_KEY_2_V1 = hex("6231306a676664397937767375646139");
    private static final byte[] SECRET_KEY_1_V23 = hex("555386fc632007aa86493522b86ae25c");
    private static final byte[] SECRET_KEY_2_V23 = hex("33079bc57a886d3cf56137096f228000");
    private static final byte[] DIGEST_SECRET_V1 = hex("70fb6c24035fdb552f38898aeede3f69");
    private static final byte[] DIGEST_SECRET_V2 = hex("93acdef76acb09857dbfe5261aab" + "cd78");
    private static final byte[] DIGEST_SECRET_V3 = hex("9c2763a9cce134766de3ff6118200553");

    private static final byte[] MESSAGE_CHALLENGE = new byte[]{0x01, 0x00};
    private static final byte[] MESSAGE_RESPONSE = new byte[]{0x01, 0x10};
    private static final byte[] MESSAGE_CHALLENGE_V4 = new byte[]{0x04, 0x00};

    private HuaweiCrypto() {
    }

    // ------------------------------------------------------------- digests

    private static byte[] digestSecret(int authVersion) {
        if (authVersion == 1 || authVersion == 4) {
            return DIGEST_SECRET_V1;
        }
        if (authVersion == 2) {
            return DIGEST_SECRET_V2;
        }
        return DIGEST_SECRET_V3;
    }

    /**
     * Returns 64 bytes: the first 32 are the challenge or answer that goes
     * on the wire, the next 16 become firstKey. Note the HMAC argument
     * order from the Java source: the digest material is the HMAC key and
     * the double nonce is the HMAC message.
     */
    public static byte[] computeDigest(int authVersion, byte[] message, byte[] doubleNonce) {
        byte[] material = concat(digestSecret(authVersion), message);
        byte[] step1 = hmacSha256(material, doubleNonce);
        byte[] head = hmacSha256(step1, doubleNonce);
        return concat(head, step1);
    }

    public static byte[] digestChallenge(int authVersion, byte[] doubleNonce) {
        if (authVersion == 4) {
            return computeDigest(authVersion, MESSAGE_CHALLENGE_V4, doubleNonce);
        }
        return computeDigest(authVersion, MESSAGE_CHALLENGE, doubleNonce);
    }

    public static byte[] digestResponse(int authVersion, byte[] doubleNonce) {
        return computeDigest(authVersion, MESSAGE_RESPONSE, doubleNonce);
    }

    // ------------------------------------------------------- bond wrapping

    /**
     * Derives the key that encrypts our stored secret key in the Bond
     * packet, from the band MAC address and two baked-in constants.
     */
    public static byte[] createSecretKeyFromMac(int authVersion, String deviceMac) {
        byte[] sk1 = authVersion == 1 ? SECRET_KEY_1_V1 : SECRET_KEY_1_V23;
        byte[] sk2 = authVersion == 1 ? SECRET_KEY_2_V1 : SECRET_KEY_2_V23;
        byte[] macKey = utf8(deviceMac.replace(":", "") + "0000");
        byte[] mixed = new byte[16];
        for (int i = 0; i < 16; i++) {
            mixed[i] = (byte) ((((sk1[i] & 0xFF) << 4) & 0xFF) ^ (sk2[i] & 0xFF));
        }
        byte[] mixedHash = sha256(mixed);
        byte[] fin = new byte[16];
        for (int i = 0; i < 16; i++) {
            fin[i] = (byte) (((mixedHash[i] & 0xFF) >> 6) ^ (macKey[i] & 0xFF));
        }
        byte[] full = sha256(fin);
        byte[] out = new byte[16];
        System.arraycopy(full, 0, out, 0, 16);
        return out;
    }

    // ----------------------------------------------------------------- AES

    public static byte[] aesEncrypt(boolean useGcm, byte[] key, byte[] iv, byte[] data) {
        return aes(useGcm, key, iv, data, true);
    }

    public static byte[] aesDecrypt(boolean useGcm, byte[] key, byte[] iv, byte[] data) {
        return aes(useGcm, key, iv, data, false);
    }

    private static byte[] aes(boolean useGcm, byte[] key, byte[] iv, byte[] data,
                              boolean encrypt) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            Cipher cipher;
            if (useGcm) {
                // ciphertext || 16-byte tag, matching Python AESGCM output.
                cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                        keySpec, new GCMParameterSpec(128, iv));
            } else {
                cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                        keySpec, new IvParameterSpec(iv));
            }
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------- TLV wrapping

    /**
     * Mints IVs as in band.py: 12 random bytes followed by the big-endian
     * uint32 of a counter that starts at the BondParams value and wraps
     * from 0xFFFFFFFF to 1.
     */
    public static final class IvGenerator {
        private long mCounter;
        private final SecureRandom mRandom = new SecureRandom();

        public IvGenerator(long startCounter) {
            mCounter = startCounter & 0xFFFFFFFFL;
        }

        public synchronized byte[] nextIv() {
            if (mCounter == 0xFFFFFFFFL) {
                mCounter = 1;
            } else {
                mCounter++;
            }
            byte[] iv = new byte[16];
            byte[] randomPart = new byte[12];
            mRandom.nextBytes(randomPart);
            System.arraycopy(randomPart, 0, iv, 0, 12);
            iv[12] = (byte) ((mCounter >> 24) & 0xFF);
            iv[13] = (byte) ((mCounter >> 16) & 0xFF);
            iv[14] = (byte) ((mCounter >> 8) & 0xFF);
            iv[15] = (byte) (mCounter & 0xFF);
            return iv;
        }

        public long counter() {
            return mCounter;
        }
    }

    public static byte[] encryptTlv(boolean useGcm, byte[] secretKey, byte[] iv,
                                    byte[] plainTlvBytes) {
        byte[] cipher = aesEncrypt(useGcm, secretKey, iv, plainTlvBytes);
        return new Tlv()
                .putByte(TAG_ENCRYPTED_FLAG, 1)
                .putRaw(TAG_IV, iv)
                .putRaw(TAG_CIPHERTEXT, cipher)
                .serialize();
    }

    public static Tlv decryptTlv(boolean useGcm, byte[] secretKey, Tlv tlv) {
        if (tlv.contains(TAG_ENCRYPTED_FLAG) && tlv.getBool(TAG_ENCRYPTED_FLAG, false)) {
            byte[] plain = aesDecrypt(useGcm, secretKey,
                    tlv.getRaw(TAG_IV), tlv.getRaw(TAG_CIPHERTEXT));
            return Tlv.parse(plain);
        }
        return tlv;
    }

    // -------------------------------------------------------------- helpers

    public static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
