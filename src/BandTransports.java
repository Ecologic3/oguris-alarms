package app.oguri.alarms;

/**
 * Picks the active band transport. Since the live proof session of
 * 2026-10-26 (PROTOCOL.md section 7.3), the active transport is the
 * direct BLE implementation. The Gadgetbridge adapter remains in the
 * codebase but is not used, and there is deliberately no automatic
 * fallback to it: if the direct path cannot work, the error surfaces
 * to the user instead.
 */
public final class BandTransports {

    private static BandTransport sActive;

    private BandTransports() {
    }

    public static synchronized BandTransport active() {
        if (sActive == null) {
            sActive = new DirectBandTransport();
        }
        return sActive;
    }
}
