package app.oguri.alarms;

import android.content.Context;

import app.oguri.alarms.band.EventAlarm;

import java.util.List;

/**
 * The one boundary between the app and the band. Everything above this
 * interface (AlarmStore, MainActivity, the backup service) is transport
 * independent and already final; everything below it is an adapter.
 *
 * Implementations:
 * - DirectBandTransport: the direct BLE implementation, ACTIVE since
 *   the live proof session of 2026-10-26 (PROTOCOL.md section 7.3
 *   settled bond takeover and auth mode). Runs one BandSession per
 *   call on a background thread; failures surface as error toasts,
 *   never as a silent fallback.
 * - GadgetbridgeBandTransport: the former fallback, kept as code only.
 *   It uses GadgetbridgeApi exactly as that class already exists; it
 *   does not change it, and nothing routes to it any more.
 *
 * Semantics every implementation must honour:
 * - setAllAlarms replaces the band's whole event alarm list with the
 *   given enabled alarms. Band slots are the list positions (index field,
 *   1..5). An empty list clears all event alarms.
 * - dismissAllAlarms clears every event alarm the app manages.
 * - setTime sets the band clock, including the current UTC offset, so
 *   alarms stay correct across DST changes.
 */
public interface BandTransport {

    /** Short name for diagnostics ("gadgetbridge" / "direct"). */
    String name();

    void setAllAlarms(Context ctx, List<EventAlarm> alarms);

    void dismissAllAlarms(Context ctx);

    void setTime(Context ctx, long epochMillis, int offsetSeconds);
}
