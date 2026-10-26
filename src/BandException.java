package app.oguri.alarms;

/**
 * A direct band session failure with a user-readable message: Bluetooth
 * off, permission missing, band unreachable, auth rejected, response
 * timeout, and so on. DirectBandTransport turns these into the app's
 * error toasts; nothing fails silently.
 */
public class BandException extends Exception {

    public BandException(String message) {
        super(message);
    }

    public BandException(String message, Throwable cause) {
        super(message, cause);
    }
}
