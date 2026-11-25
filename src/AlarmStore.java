package app.oguri.alarms;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persists per-alarm state (name, enabled, time, repeat-days bitmask) in
 * SharedPreferences. Days bitmask uses the Calendar constant as the bit
 * index: bit (1 << dayOfWeek), where dayOfWeek is Calendar.SUNDAY (1) ..
 * Calendar.SATURDAY (7).
 *
 * Alarms are internally keyed by a stable id. The four original alarms
 * keep their ids (a1/a2/a3/dyn) forever, so an upgrade from the fixed-slot
 * versions migrates with no loss: the ordered id list is stored under
 * "alarm_ids", and the first read after an upgrade seeds it with the four
 * original ids, whose per-alarm keys already hold the user's data.
 * Alarms added later get ids u1, u2, ... and their own per-alarm keys.
 * The list is capped at MAX_ALARMS, the band's event alarm limit.
 *
 * The user-visible name doubles as the band alarm title.
 */
public final class AlarmStore {

    public static final String ID_A1 = "a1";
    public static final String ID_A2 = "a2";
    public static final String ID_A3 = "a3";
    public static final String ID_DYN = "dyn";

    public static final String[] IDS = {ID_A1, ID_A2, ID_A3, ID_DYN};

    /** The Honor Band 4 holds at most 5 event alarms. */
    public static final int MAX_ALARMS = 5;

    private static final String KEY_ALARM_IDS = "alarm_ids";
    private static final String KEY_NEXT_USER_SEQ = "next_user_seq";
    private static final String KEY_LAST_BACKUP_OUTCOME = "last_backup_outcome";
    private static final String KEY_BACKUP_WINDOW_ACTIVE = "backup_window_active";
    private static final String KEY_BACKUP_WINDOW_ALARM_ID = "backup_window_alarm_id";
    private static final String KEY_BACKUP_RINGING = "backup_ringing";
    private static final String KEY_BAND_BATTERY_LEVEL = "band_battery_level";
    private static final String KEY_BAND_BATTERY_READ_AT = "band_battery_read_at";

    private AlarmStore() {
    }

    /** The built-in name of a slot, used when the user has not renamed it. */
    public static String defaultNameFor(String id) {
        if (ID_A1.equals(id)) {
            return "A1";
        }
        if (ID_A2.equals(id)) {
            return "A2";
        }
        if (ID_A3.equals(id)) {
            return "A3";
        }
        if (ID_DYN.equals(id)) {
            return "Dynamic";
        }
        return "Alarm";
    }

    /** The current user-visible name of an alarm (its Gadgetbridge title). */
    public static String getName(Context ctx, String id) {
        String name = prefs(ctx).getString("name_" + id, null);
        if (name != null) {
            name = name.trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return defaultNameFor(id);
    }

    /**
     * Renames an alarm. A null or blank name removes the custom name, which
     * restores the slot default.
     */
    public static void setName(Context ctx, String id, String name) {
        SharedPreferences.Editor editor = prefs(ctx).edit();
        if (name == null || name.trim().isEmpty()) {
            editor.remove("name_" + id);
        } else {
            editor.putString("name_" + id, name.trim());
        }
        editor.apply();
    }

    public static boolean isDynamic(String id) {
        return ID_DYN.equals(id);
    }

    // ------------------------------------------------------------ alarm list

    /**
     * The ordered list of alarm ids. On the first read after an upgrade
     * from a fixed-slot version (no stored list yet), the list is seeded
     * with the four original ids; their stored times, days, names and
     * enabled states live under the same per-alarm keys as before, so the
     * migration loses nothing.
     */
    public static List<String> getIds(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        List<String> ids = new ArrayList<String>();
        if (!sp.contains(KEY_ALARM_IDS)) {
            for (String id : IDS) {
                ids.add(id);
            }
            sp.edit().putString(KEY_ALARM_IDS, joinIds(ids)).apply();
            return ids;
        }
        String stored = sp.getString(KEY_ALARM_IDS, "");
        if (stored != null) {
            for (String part : stored.split(",")) {
                String id = part.trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    public static int getAlarmCount(Context ctx) {
        return getIds(ctx).size();
    }

    /**
     * Creates a new alarm and returns its id, or null when the band limit
     * of MAX_ALARMS is already reached. The new alarm starts disabled at
     * 07:00, Monday to Friday, with the first free A-number name (A4, A5,
     * ...; falling back to "Alarm N" when every A-number is taken), so its
     * title never collides with an existing alarm's title.
     */
    public static String createAlarm(Context ctx) {
        List<String> ids = getIds(ctx);
        if (ids.size() >= MAX_ALARMS) {
            return null;
        }
        SharedPreferences sp = prefs(ctx);
        int seq = sp.getInt(KEY_NEXT_USER_SEQ, 1);
        String id;
        do {
            id = "u" + seq;
            seq++;
        } while (ids.contains(id) || sp.contains("name_" + id));
        Set<String> usedNames = new HashSet<String>();
        for (String existing : ids) {
            usedNames.add(getName(ctx, existing));
        }
        String name = null;
        for (int n = 1; n < 100; n++) {
            if (!usedNames.contains("A" + n)) {
                name = "A" + n;
                break;
            }
        }
        if (name == null) {
            for (int n = 1; n < 100; n++) {
                if (!usedNames.contains("Alarm " + n)) {
                    name = "Alarm " + n;
                    break;
                }
            }
        }
        ids.add(id);
        sp.edit()
                .putString(KEY_ALARM_IDS, joinIds(ids))
                .putInt(KEY_NEXT_USER_SEQ, seq)
                .putString("name_" + id, name)
                .putBoolean("enabled_" + id, false)
                .putInt("hour_" + id, 7)
                .putInt("minute_" + id, 0)
                .putInt("days_" + id, defaultDaysMask(id))
                .apply();
        return id;
    }

    /** Removes an alarm and every stored key that belongs to it. */
    public static void removeAlarm(Context ctx, String id) {
        List<String> ids = getIds(ctx);
        ids.remove(id);
        prefs(ctx).edit()
                .putString(KEY_ALARM_IDS, joinIds(ids))
                .remove("name_" + id)
                .remove("enabled_" + id)
                .remove("hour_" + id)
                .remove("minute_" + id)
                .remove("days_" + id)
                .apply();
    }

    private static String joinIds(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------- backup outcome

    /**
     * Records the latest phone backup outcome with a timestamp, so the app
     * can show what the backup actually did last time (BackupService used
     * to leave no trace at all when a run went wrong).
     */
    public static void setLastBackupOutcome(Context ctx, String outcome) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date());
        prefs(ctx).edit()
                .putString(KEY_LAST_BACKUP_OUTCOME, stamp + " - " + outcome)
                .apply();
    }

    /** The latest recorded backup outcome, or a placeholder when none ran. */
    public static String getLastBackupOutcome(Context ctx) {
        String value = prefs(ctx).getString(KEY_LAST_BACKUP_OUTCOME, null);
        if (value == null || value.trim().isEmpty()) {
            return "No backup run recorded yet";
        }
        return value;
    }

    // ------------------------------------------------------- backup window

    /**
     * Persists the live backup window (the watch between a band alarm
     * firing at T and the backup resolving at or before T+3). The ring
     * and cap timers are AlarmManager alarms delivered to a possibly
     * restarted process, so this state is the authority they check
     * before acting: a delivery counts only while a window is active
     * for the same alarm id. Set when a watch starts, cleared when the
     * window finishes.
     */
    public static void setBackupWindow(Context ctx, String id) {
        prefs(ctx).edit()
                .putBoolean(KEY_BACKUP_WINDOW_ACTIVE, true)
                .putString(KEY_BACKUP_WINDOW_ALARM_ID, id)
                .apply();
    }

    /** Clears the persisted backup window (the window has finished). */
    public static void clearBackupWindow(Context ctx) {
        prefs(ctx).edit()
                .putBoolean(KEY_BACKUP_WINDOW_ACTIVE, false)
                .remove(KEY_BACKUP_WINDOW_ALARM_ID)
                .apply();
    }

    /**
     * The alarm id of the currently active backup window, or null when
     * no window is active.
     */
    public static String getBackupWindowAlarmId(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        if (!sp.getBoolean(KEY_BACKUP_WINDOW_ACTIVE, false)) {
            return null;
        }
        return sp.getString(KEY_BACKUP_WINDOW_ALARM_ID, null);
    }

    /**
     * Persists whether a backup is ringing right now. Set when ringing
     * starts, cleared at every window finish and when a new watch
     * starts, so it can only be stale when the process died mid-ring;
     * MainActivity checks it on resume because opening the app means
     * The user is awake and interacting, so a still-ringing backup stops.
     */
    public static void setBackupRinging(Context ctx, boolean ringing) {
        prefs(ctx).edit().putBoolean(KEY_BACKUP_RINGING, ringing).apply();
    }

    /** Whether the last recorded backup state is "ringing now". */
    public static boolean isBackupRinging(Context ctx) {
        return prefs(ctx).getBoolean(KEY_BACKUP_RINGING, false);
    }

    // -------------------------------------------------------- band battery

    /**
     * Stores the band battery level from the latest successful band
     * session, with the wall-clock time it was read. The band is only
     * queried during sessions (alarm syncs and time syncs), so this is
     * a last-known value, shown in the UI with its read time.
     */
    public static void setBandBatteryLevel(Context ctx, int level, long readAtMillis) {
        prefs(ctx).edit()
                .putInt(KEY_BAND_BATTERY_LEVEL, level)
                .putLong(KEY_BAND_BATTERY_READ_AT, readAtMillis)
                .apply();
    }

    /** The last known band battery level in percent, or -1 when never read. */
    public static int getBandBatteryLevel(Context ctx) {
        return prefs(ctx).getInt(KEY_BAND_BATTERY_LEVEL, -1);
    }

    /** When the stored band battery level was read (millis), or 0 when never. */
    public static long getBandBatteryReadAt(Context ctx) {
        return prefs(ctx).getLong(KEY_BAND_BATTERY_READ_AT, 0L);
    }

    /**
     * Stores the custom backup sound chosen in the system ringtone picker.
     * A null Uri clears the custom value, which means "system default".
     */
    public static void setBackupSoundUri(Context ctx, Uri uri) {
        SharedPreferences.Editor editor = prefs(ctx).edit();
        if (uri == null) {
            editor.remove("backup_sound_uri");
        } else {
            editor.putString("backup_sound_uri", uri.toString());
        }
        editor.apply();
    }

    /**
     * The sound the backup actually plays: the stored custom Uri when one
     * is set, otherwise the system default alarm tone, otherwise the
     * system default notification tone.
     */
    public static Uri getEffectiveBackupSoundUri(Context ctx) {
        String stored = prefs(ctx).getString("backup_sound_uri", null);
        if (stored != null && !stored.trim().isEmpty()) {
            try {
                return Uri.parse(stored);
            } catch (Exception ignored) {
                // A corrupt stored value falls through to the defaults.
            }
        }
        Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarm != null) {
            return alarm;
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    /** The display title of the effective backup sound. */
    public static String getBackupSoundTitle(Context ctx) {
        Uri uri = getEffectiveBackupSoundUri(ctx);
        if (uri == null) {
            return "None";
        }
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(ctx, uri);
            if (ringtone != null) {
                String title = ringtone.getTitle(ctx);
                if (title != null && !title.trim().isEmpty()) {
                    return title;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the generic label below.
        }
        return "System default";
    }

    /** Bitmask with every weekday (Sunday..Saturday) set. */
    public static int allDaysMask() {
        int mask = 0;
        for (int day = Calendar.SUNDAY; day <= Calendar.SATURDAY; day++) {
            mask |= (1 << day);
        }
        return mask;
    }

    /**
     * The days an alarm actually fires on. Dynamic is hardwired to all 7
     * days (no days are ever picked for it), and a fixed alarm with no day
     * chips selected is treated as all 7 too, so a SET_ALARM broadcast is
     * never sent without a days list.
     */
    public static int effectiveDaysMask(Context ctx, String id) {
        if (isDynamic(id)) {
            return allDaysMask();
        }
        int mask = getDaysMask(ctx, id);
        return mask == 0 ? allDaysMask() : mask;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx, String id) {
        return prefs(ctx).getBoolean("enabled_" + id, false);
    }

    public static void setEnabled(Context ctx, String id, boolean value) {
        prefs(ctx).edit().putBoolean("enabled_" + id, value).apply();
    }

    public static int getHour(Context ctx, String id) {
        return prefs(ctx).getInt("hour_" + id, defaultHour(id));
    }

    public static int getMinute(Context ctx, String id) {
        return prefs(ctx).getInt("minute_" + id, defaultMinute(id));
    }

    public static void setTime(Context ctx, String id, int hour, int minute) {
        prefs(ctx).edit()
                .putInt("hour_" + id, hour)
                .putInt("minute_" + id, minute)
                .apply();
    }

    public static int getDaysMask(Context ctx, String id) {
        return prefs(ctx).getInt("days_" + id, defaultDaysMask(id));
    }

    public static void setDaysMask(Context ctx, String id, int mask) {
        prefs(ctx).edit().putInt("days_" + id, mask).apply();
    }

    private static int defaultHour(String id) {
        if (ID_A1.equals(id)) {
            return 6;
        }
        if (ID_A2.equals(id)) {
            return 7;
        }
        if (ID_A3.equals(id)) {
            return 8;
        }
        return 7;
    }

    private static int defaultMinute(String id) {
        if (ID_A1.equals(id)) {
            return 30;
        }
        if (ID_A2.equals(id)) {
            return 15;
        }
        if (ID_A3.equals(id)) {
            return 30;
        }
        return 0;
    }

    private static int defaultDaysMask(String id) {
        if (ID_DYN.equals(id)) {
            return 0;
        }
        int mask = 0;
        if (ID_A3.equals(id)) {
            mask |= (1 << Calendar.SATURDAY) | (1 << Calendar.SUNDAY);
        } else {
            mask |= (1 << Calendar.MONDAY) | (1 << Calendar.TUESDAY)
                    | (1 << Calendar.WEDNESDAY) | (1 << Calendar.THURSDAY)
                    | (1 << Calendar.FRIDAY);
        }
        return mask;
    }

    /**
     * Converts a days bitmask into the ArrayList of Calendar constants
     * the band adapters expect, or null when the mask is empty. Callers
     * pass effectiveDaysMask, which is never empty, so in practice the
     * list is always produced.
     */
    public static ArrayList<Integer> daysList(int mask) {
        if (mask == 0) {
            return null;
        }
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (int day = Calendar.SUNDAY; day <= Calendar.SATURDAY; day++) {
            if ((mask & (1 << day)) != 0) {
                out.add(Integer.valueOf(day));
            }
        }
        return out;
    }
}
