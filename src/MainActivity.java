package app.oguri.alarms;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Oguri's Alarms: one screen with a card per alarm. Alarms are a managed
 * list (AlarmStore, capped at the band's 5 event alarms); every band
 * change goes through the BandTransport boundary (BandTransports.active)
 * as a full set-all sync, and each enabled alarm arms the phone sound
 * backup. The activity never touches a transport API directly.
 */
public class MainActivity extends Activity {

    private static final int COLOR_ACCENT = 0xFF8B5CF6;
    private static final int COLOR_ACCENT_LIGHT = 0xFFA78BFA;
    private static final int COLOR_TEXT = 0xFFF5F3FF;
    private static final int COLOR_TEXT_DIM = 0xFFC4B5FD;

    private static final int[] DAY_ORDER = {
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    };
    private static final String[] DAY_LABELS = {"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};

    private static final int REQUEST_PICK_BACKUP_SOUND = 2001;
    private static final long TEST_SOUND_MS = 3000L;

    private final List<CardViews> mCards = new ArrayList<CardViews>();
    private boolean mSuppressSwitchEvents;
    private LinearLayout mCardsContainer;
    private Button mAddButton;
    private Button mExactButton;
    private Button mBatteryButton;
    private TextView mSetupStatus;
    private TextView mLastOutcome;
    private TextView mBandStatus;
    private TextView mBandStatusTime;
    private BatteryGlyphView mBandBatteryGlyph;
    private TextView mSummaryText;
    private TextView mSoundTitle;
    private MediaPlayer mTestPlayer;
    private final Handler mTestHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTestStopRunnable = new Runnable() {
        @Override
        public void run() {
            stopTestSound();
        }
    };

    private static class CardViews {
        String id;
        LinearLayout root;
        TextView titleText;
        TextView pill;
        Switch toggle;
        TextView timeText;
        TextView statusText;
        TextView[] chips; // null for the Dynamic card
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF150A2E);
        getWindow().setNavigationBarColor(0xFF2A1065);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        requestBluetoothPermissionIfNeeded();
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopRingingBackupIfNeeded();
        refreshAll();
    }

    /**
     * Opening the app means the user is awake and interacting, so a backup
     * that is ringing right now stops: BackupService persists the
     * ringing state, and finding it set here sends its Stop action.
     * The flag is cleared when the window finishes and when a new
     * watch starts, so this can only misfire on a process that died
     * mid-ring, and BackupService treats a stop for a window it never
     * saw as a silent flag clear.
     */
    private void stopRingingBackupIfNeeded() {
        if (!AlarmStore.isBackupRinging(this)) {
            return;
        }
        Intent intent = new Intent(this, BackupService.class);
        intent.setAction(BackupService.ACTION_STOP);
        startService(intent);
    }

    @Override
    protected void onDestroy() {
        stopTestSound();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_BACKUP_SOUND || resultCode != RESULT_OK) {
            return;
        }
        Uri picked = data != null
                ? (Uri) data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                : null;
        // A null picked Uri means the user chose the system default in
        // the picker; AlarmStore treats null as "clear the custom sound".
        AlarmStore.setBackupSoundUri(this, picked);
        refreshSetupStatus();
    }

    // ------------------------------------------------------------------- UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(gradientBackground());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView header = new TextView(this);
        header.setText("Oguri's Alarms");
        header.setTextSize(30);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setTextColor(COLOR_TEXT);
        headerRow.addView(header, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        try {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(getPackageManager().getApplicationIcon(
                    getApplicationInfo()));
            headerRow.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        } catch (Exception ignored) {
            // No icon is cosmetic only; the header works without it.
        }

        // Slim band status row directly under the header: the last
        // battery level read from the band (during alarm/time sync
        // sessions) and the time it was read.
        LinearLayout bandRow = new LinearLayout(this);
        bandRow.setOrientation(LinearLayout.HORIZONTAL);
        bandRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bandRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bandRowParams.topMargin = dp(6);
        root.addView(bandRow, bandRowParams);

        mBandBatteryGlyph = new BatteryGlyphView(this);
        LinearLayout.LayoutParams glyphParams = new LinearLayout.LayoutParams(dp(26), dp(14));
        glyphParams.rightMargin = dp(6);
        bandRow.addView(mBandBatteryGlyph, glyphParams);

        mBandStatus = new TextView(this);
        mBandStatus.setTextSize(13);
        mBandStatus.setTextColor(COLOR_TEXT_DIM);
        bandRow.addView(mBandStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mBandStatusTime = new TextView(this);
        mBandStatusTime.setTextSize(13);
        mBandStatusTime.setTextColor(COLOR_TEXT_DIM);
        bandRow.addView(mBandStatusTime, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mSummaryText = new TextView(this);
        mSummaryText.setTextSize(13);
        mSummaryText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mSummaryText.setTextColor(COLOR_ACCENT_LIGHT);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(8);
        summaryParams.bottomMargin = dp(20);
        root.addView(mSummaryText, summaryParams);

        mCardsContainer = new LinearLayout(this);
        mCardsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mCardsContainer);
        rebuildCards();

        mAddButton = makeButton("Add alarm");
        mAddButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAddAlarmClicked();
            }
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.bottomMargin = dp(16);
        mAddButton.setLayoutParams(addParams);
        root.addView(mAddButton);

        root.addView(buildSetupSection());
        return scroll;
    }

    /** Rebuilds every card from the current AlarmStore list. */
    private void rebuildCards() {
        if (mCardsContainer == null) {
            return;
        }
        mCardsContainer.removeAllViews();
        mCards.clear();
        for (String id : AlarmStore.getIds(this)) {
            mCardsContainer.addView(buildCard(id));
        }
    }

    private View buildCard(final String id) {
        final CardViews views = new CardViews();
        views.id = id;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(glassBackground());
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(16);
        card.setLayoutParams(cardParams);
        views.root = card;

        // Title row: name + rename button + ON/OFF pill + enable switch
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(COLOR_TEXT);
        views.titleText = title;
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button editButton = new Button(this);
        editButton.setText("✎");
        editButton.setTextSize(13);
        editButton.setTextColor(COLOR_TEXT);
        editButton.setAllCaps(false);
        editButton.setPadding(0, 0, 0, 0);
        editButton.setMinWidth(0);
        editButton.setMinimumWidth(0);
        editButton.setMinHeight(0);
        editButton.setMinimumHeight(0);
        GradientDrawable editBg = new GradientDrawable();
        editBg.setColor(0x26FFFFFF);
        editBg.setCornerRadius(dp(12));
        editButton.setBackground(editBg);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(dp(40), dp(36));
        editParams.rightMargin = dp(8);
        editButton.setLayoutParams(editParams);
        editButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRenameDialog(id);
            }
        });
        titleRow.addView(editButton);

        TextView pill = new TextView(this);
        pill.setTextSize(11);
        pill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.rightMargin = dp(10);
        pill.setLayoutParams(pillParams);
        views.pill = pill;
        titleRow.addView(pill);

        Switch toggle = new Switch(this);
        // High contrast in both states: the old violet track (COLOR_ACCENT)
        // blended into the violet card, above all on the bright enabled
        // cards. ON = solid white track with a deep purple thumb on the
        // enabled card's violet gradient; OFF = dark plum track with a pale
        // lavender thumb on the dim disabled card.
        toggle.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF4C1D95, 0xFFEDE9FE}));
        toggle.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFFFFFFF, 0x8C1B1035}));
        views.toggle = toggle;
        titleRow.addView(toggle);
        card.addView(titleRow);

        // Dynamic fires daily; it never asks for days
        if (AlarmStore.isDynamic(id)) {
            TextView dynamicNote = new TextView(this);
            dynamicNote.setText("Every day · just set the time and toggle, no day picking");
            dynamicNote.setTextSize(12);
            dynamicNote.setTextColor(COLOR_TEXT_DIM);
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noteParams.topMargin = dp(4);
            card.addView(dynamicNote, noteParams);
        }

        // Big time (tap to change)
        TextView time = new TextView(this);
        time.setTextSize(38);
        time.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        time.setTextColor(COLOR_TEXT);
        time.setPadding(0, dp(6), 0, 0);
        views.timeText = time;
        time.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTimePicker(id);
            }
        });
        card.addView(time);

        // Day chips for the fixed alarms
        if (!AlarmStore.isDynamic(id)) {
            LinearLayout chipRow = new LinearLayout(this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams chipRowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipRowParams.topMargin = dp(10);
            views.chips = new TextView[DAY_ORDER.length];
            for (int i = 0; i < DAY_ORDER.length; i++) {
                final int index = i;
                TextView chip = new TextView(this);
                chip.setText(DAY_LABELS[i]);
                chip.setTextSize(13);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(0, dp(7), 0, dp(7));
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) {
                    chipParams.leftMargin = dp(6);
                }
                chip.setLayoutParams(chipParams);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onDayChipClicked(id, DAY_ORDER[index]);
                    }
                });
                views.chips[i] = chip;
                chipRow.addView(chip);
            }
            card.addView(chipRow, chipRowParams);
        }

        // Status line (next backup / off)
        TextView status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(COLOR_TEXT_DIM);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(10);
        views.statusText = status;
        card.addView(status, statusParams);

        Button deleteButton = new Button(this);
        deleteButton.setText("Delete alarm");
        deleteButton.setTextSize(12);
        deleteButton.setTextColor(COLOR_TEXT_DIM);
        deleteButton.setAllCaps(false);
        deleteButton.setPadding(0, 0, 0, 0);
        deleteButton.setMinWidth(0);
        deleteButton.setMinimumWidth(0);
        GradientDrawable deleteBg = new GradientDrawable();
        deleteBg.setColor(0x26FFFFFF);
        deleteBg.setCornerRadius(dp(12));
        deleteButton.setBackground(deleteBg);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                dp(120), dp(36));
        deleteParams.topMargin = dp(10);
        deleteButton.setLayoutParams(deleteParams);
        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDeleteDialog(id);
            }
        });
        card.addView(deleteButton);

        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mSuppressSwitchEvents) {
                    return;
                }
                onToggleChanged(id, isChecked);
            }
        });

        mCards.add(views);
        return card;
    }

    private View buildSetupSection() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(glassBackground());
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardParams);

        TextView heading = new TextView(this);
        heading.setText("Setup");
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setTextColor(COLOR_TEXT);
        card.addView(heading);

        mSetupStatus = new TextView(this);
        mSetupStatus.setTextSize(13);
        mSetupStatus.setTextColor(COLOR_TEXT_DIM);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(6);
        statusParams.bottomMargin = dp(6);
        card.addView(mSetupStatus, statusParams);

        mLastOutcome = new TextView(this);
        mLastOutcome.setTextSize(13);
        mLastOutcome.setTextColor(COLOR_TEXT_DIM);
        LinearLayout.LayoutParams outcomeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outcomeParams.topMargin = dp(4);
        outcomeParams.bottomMargin = dp(6);
        card.addView(mLastOutcome, outcomeParams);

        mExactButton = makeButton("Exact alarm permission");
        mExactButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openExactAlarmSettings();
            }
        });
        card.addView(mExactButton);

        mBatteryButton = makeButton("Battery optimisation exemption");
        mBatteryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        });
        card.addView(mBatteryButton);

        TextView soundLabel = new TextView(this);
        soundLabel.setText("Sound");
        soundLabel.setTextSize(15);
        soundLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        soundLabel.setTextColor(COLOR_TEXT);
        LinearLayout.LayoutParams soundLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        soundLabelParams.topMargin = dp(12);
        card.addView(soundLabel, soundLabelParams);

        LinearLayout soundRow = new LinearLayout(this);
        soundRow.setOrientation(LinearLayout.HORIZONTAL);
        soundRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams soundRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        soundRowParams.topMargin = dp(4);
        soundRowParams.bottomMargin = dp(4);

        mSoundTitle = new TextView(this);
        mSoundTitle.setTextSize(13);
        mSoundTitle.setTextColor(COLOR_TEXT_DIM);
        soundRow.addView(mSoundTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button changeButton = makeButton("Change");
        LinearLayout.LayoutParams changeParams = new LinearLayout.LayoutParams(
                dp(104), ViewGroup.LayoutParams.WRAP_CONTENT);
        changeButton.setLayoutParams(changeParams);
        changeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSoundPicker();
            }
        });
        soundRow.addView(changeButton);
        card.addView(soundRow, soundRowParams);

        Button testButton = makeButton("Test sound");
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playTestSound();
            }
        });
        card.addView(testButton);

        return card;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_ACCENT);
        bg.setCornerRadius(dp(14));
        button.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable gradientBackground() {
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF150A2E, 0xFF2A1065, 0xFF4C1D95});
        return gradient;
    }

    private GradientDrawable glassBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x2EFFFFFF);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), 0x59FFFFFF);
        return bg;
    }

    /** Bright gradient for an enabled card, muted glass for a disabled one. */
    private GradientDrawable cardBackground(boolean enabled) {
        GradientDrawable bg;
        if (enabled) {
            bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF5B21B6, 0xFF7C3AED});
            bg.setStroke(dp(2), COLOR_ACCENT_LIGHT);
        } else {
            bg = new GradientDrawable();
            bg.setColor(0x1AFFFFFF);
            bg.setStroke(dp(1), 0x40FFFFFF);
        }
        bg.setCornerRadius(dp(24));
        return bg;
    }

    private GradientDrawable pillBackground(boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? COLOR_ACCENT : 0x40FFFFFF);
        bg.setCornerRadius(dp(10));
        return bg;
    }

    private GradientDrawable chipBackground(boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? COLOR_ACCENT : 0x26FFFFFF);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), selected ? COLOR_ACCENT_LIGHT : 0x4DFFFFFF);
        return bg;
    }

    // -------------------------------------------------------------- behaviour

    private void onToggleChanged(String id, boolean on) {
        AlarmStore.setEnabled(this, id, on);
        syncBandAlarms();
        if (on) {
            BackupScheduler.arm(this, id);
        } else {
            BackupScheduler.disarm(this, id);
        }
        refreshCard(id);
        refreshSummary();
        Toast.makeText(this, AlarmStore.getName(this, id) + (on ? " on" : " off"),
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- add/delete

    private void onAddAlarmClicked() {
        String id = AlarmStore.createAlarm(this);
        if (id == null) {
            Toast.makeText(this, "Band limit reached: max "
                    + AlarmStore.MAX_ALARMS + " alarms", Toast.LENGTH_LONG).show();
            refreshAddButton();
            return;
        }
        rebuildCards();
        refreshAll();
        Toast.makeText(this, "Added " + AlarmStore.getName(this, id),
                Toast.LENGTH_SHORT).show();
    }

    private void showDeleteDialog(final String id) {
        final String name = AlarmStore.getName(this, id);
        new AlertDialog.Builder(this)
                .setTitle("Delete alarm")
                .setMessage("Delete " + name + "?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteAlarm(id, name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAlarm(String id, String name) {
        boolean wasEnabled = AlarmStore.isEnabled(this, id);
        if (wasEnabled) {
            // The band alarm goes first (one clear through the transport
            // while this alarm's title is still stored), then the alarm is
            // removed and the remaining enabled alarms are synced back.
            BandTransports.active().dismissAllAlarms(this);
            BackupScheduler.disarm(this, id);
        }
        AlarmStore.removeAlarm(this, id);
        if (wasEnabled) {
            syncBandAlarms();
        }
        rebuildCards();
        refreshAll();
        Toast.makeText(this, "Deleted " + name, Toast.LENGTH_SHORT).show();
    }

    private void refreshAddButton() {
        if (mAddButton == null) {
            return;
        }
        boolean atCap = AlarmStore.getAlarmCount(this) >= AlarmStore.MAX_ALARMS;
        mAddButton.setEnabled(!atCap);
        mAddButton.setAlpha(atCap ? 0.5f : 1f);
        mAddButton.setText(atCap
                ? "Add alarm (max " + AlarmStore.MAX_ALARMS + " reached)"
                : "Add alarm");
    }

    private void onDayChipClicked(String id, int calendarDay) {
        int mask = AlarmStore.getDaysMask(this, id);
        if ((mask & (1 << calendarDay)) != 0) {
            mask &= ~(1 << calendarDay);
        } else {
            mask |= (1 << calendarDay);
        }
        AlarmStore.setDaysMask(this, id, mask);
        if (AlarmStore.isEnabled(this, id)) {
            resendAlarm(id);
        }
        refreshCard(id);
        refreshSummary();
    }

    private void showTimePicker(final String id) {
        TimePickerDialog dialog = new TimePickerDialog(this,
                new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                        AlarmStore.setTime(MainActivity.this, id, hourOfDay, minute);
                        if (AlarmStore.isEnabled(MainActivity.this, id)) {
                            resendAlarm(id);
                        }
                        refreshCard(id);
                        refreshSummary();
                    }
                },
                AlarmStore.getHour(this, id), AlarmStore.getMinute(this, id), true);
        dialog.show();
    }

    /** Re-sends the band state after an enabled alarm's time/days changed. */
    private void resendAlarm(String id) {
        syncBandAlarms();
        BackupScheduler.arm(this, id);
    }

    /**
     * Pushes the full enabled alarm list plus the current time through the
     * active BandTransport. The list is never day-less: effectiveDaysMask
     * resolves Dynamic and fixed alarms with no chips selected to all 7
     * days, and BandAlarmMapper carries that into the band model.
     */
    private void syncBandAlarms() {
        BandTransport transport = BandTransports.active();
        transport.setAllAlarms(this, BandAlarmMapper.enabledAlarms(this));
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        long now = System.currentTimeMillis();
        transport.setTime(this, now, tz.getOffset(now) / 1000);
    }

    // ----------------------------------------------------------------- rename

    private void showRenameDialog(final String id) {
        final String oldName = AlarmStore.getName(this, id);
        final EditText input = new EditText(this);
        input.setText(oldName);
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        input.setHint(AlarmStore.defaultNameFor(id));
        new AlertDialog.Builder(this)
                .setTitle("Rename alarm")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applyRename(id, oldName, input.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyRename(String id, String oldName, String rawName) {
        String trimmed = rawName == null ? "" : rawName.trim();
        String newName = trimmed.isEmpty() ? AlarmStore.defaultNameFor(id) : trimmed;
        boolean changed = !newName.equals(oldName);
        boolean enabled = AlarmStore.isEnabled(this, id);
        if (changed && enabled) {
            // The band title IS the name, so clear the band while the old
            // title is still stored, then store the new name and sync the
            // full list back under it.
            BandTransports.active().dismissAllAlarms(this);
        }
        AlarmStore.setName(this, id, rawName);
        if (changed && enabled) {
            syncBandAlarms();
            BackupScheduler.arm(this, id);
        }
        refreshCard(id);
        refreshSummary();
    }

    // ---------------------------------------------------------------- refresh

    private void refreshAll() {
        for (CardViews views : mCards) {
            refreshCard(views.id);
        }
        refreshSummary();
        refreshBandStatus();
        refreshSetupStatus();
        refreshAddButton();
    }

    /**
     * The band status row under the header: the last battery level a
     * band session read, and when. Before the first successful read
     * it says the battery has not been checked yet and shows no time.
     */
    private void refreshBandStatus() {
        if (mBandStatus == null || mBandStatusTime == null) {
            return;
        }
        int level = AlarmStore.getBandBatteryLevel(this);
        if (mBandBatteryGlyph != null) {
            mBandBatteryGlyph.setLevel(level);
        }
        if (level < 0) {
            mBandStatus.setText("Honor Band 4 · Battery not checked yet");
            mBandStatusTime.setText("");
            return;
        }
        mBandStatus.setText("Honor Band 4 · Battery " + level + "%");
        long readAt = AlarmStore.getBandBatteryReadAt(this);
        mBandStatusTime.setText(readAt > 0
                ? "updated " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(readAt))
                : "");
    }

    private void refreshCard(String id) {
        CardViews views = null;
        for (CardViews candidate : mCards) {
            if (candidate.id.equals(id)) {
                views = candidate;
                break;
            }
        }
        if (views == null) {
            return;
        }
        boolean enabled = AlarmStore.isEnabled(this, id);
        mSuppressSwitchEvents = true;
        views.toggle.setChecked(enabled);
        mSuppressSwitchEvents = false;

        // Obvious state: bright card + full-opacity text + ON pill when
        // enabled; muted card, dimmed text and a grey OFF pill when not.
        views.root.setBackground(cardBackground(enabled));
        float textAlpha = enabled ? 1f : 0.55f;
        views.titleText.setText(AlarmStore.getName(this, id));
        views.titleText.setAlpha(textAlpha);
        views.timeText.setAlpha(textAlpha);
        views.statusText.setAlpha(textAlpha);
        views.pill.setText(enabled ? "ON" : "OFF");
        views.pill.setBackground(pillBackground(enabled));
        views.pill.setTextColor(enabled ? Color.WHITE : 0xFFDDD6FE);

        views.timeText.setText(String.format(Locale.US, "%02d:%02d",
                AlarmStore.getHour(this, id), AlarmStore.getMinute(this, id)));

        if (views.chips != null) {
            int mask = AlarmStore.getDaysMask(this, id);
            for (int i = 0; i < DAY_ORDER.length; i++) {
                boolean selected = (mask & (1 << DAY_ORDER[i])) != 0;
                views.chips[i].setBackground(chipBackground(selected));
                views.chips[i].setTextColor(selected ? Color.WHITE : COLOR_TEXT_DIM);
                views.chips[i].setAlpha(textAlpha);
            }
        }

        if (enabled) {
            int mask = AlarmStore.effectiveDaysMask(this, id);
            long next = BackupScheduler.nextOccurrenceMillis(
                    AlarmStore.getHour(this, id), AlarmStore.getMinute(this, id), mask);
            String when = new SimpleDateFormat("EEE HH:mm", Locale.US).format(new Date(next));
            views.statusText.setText("Band alarm set, backup next: " + when);
        } else {
            views.statusText.setText("Off");
        }
    }

    /** The "Armed: ... · Next: ..." line directly under the header. */
    private void refreshSummary() {
        if (mSummaryText == null) {
            return;
        }
        List<String> armedNames = new ArrayList<String>();
        String nextName = null;
        long nextAt = Long.MAX_VALUE;
        for (String id : AlarmStore.getIds(this)) {
            if (!AlarmStore.isEnabled(this, id)) {
                continue;
            }
            armedNames.add(AlarmStore.getName(this, id));
            long next = BackupScheduler.nextOccurrenceMillis(
                    AlarmStore.getHour(this, id), AlarmStore.getMinute(this, id),
                    AlarmStore.effectiveDaysMask(this, id));
            if (next < nextAt) {
                nextAt = next;
                nextName = AlarmStore.getName(this, id);
            }
        }
        if (armedNames.isEmpty()) {
            mSummaryText.setText("No alarms armed");
            return;
        }
        StringBuilder sb = new StringBuilder("Armed: ");
        for (int i = 0; i < armedNames.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(armedNames.get(i));
        }
        sb.append(" · Next: ").append(nextName).append(' ').append(describeWhen(nextAt));
        mSummaryText.setText(sb.toString());
    }

    /** "today 07:00" / "tomorrow 07:00" / "Mon 07:00" for a fire moment. */
    private static String describeWhen(long millis) {
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(millis));
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        Calendar day = Calendar.getInstance();
        if (then.get(Calendar.YEAR) == day.get(Calendar.YEAR)
                && then.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)) {
            return "today " + time;
        }
        day.add(Calendar.DAY_OF_YEAR, 1);
        if (then.get(Calendar.YEAR) == day.get(Calendar.YEAR)
                && then.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)) {
            return "tomorrow " + time;
        }
        return new SimpleDateFormat("EEE ", Locale.US).format(new Date(millis)) + time;
    }

    private void refreshSetupStatus() {
        if (mSetupStatus == null) {
            return;
        }
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        boolean exactOk = am != null && am.canScheduleExactAlarms();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batteryOk = pm != null
                && pm.isIgnoringBatteryOptimizations(getPackageName());
        mSetupStatus.setText("Exact alarms: " + (exactOk ? "allowed" : "NOT allowed")
                + "\nBattery optimisation: " + (batteryOk ? "exempt" : "NOT exempt"));
        // A granted permission must not keep shouting for attention: fade
        // its button (still tappable, in case the permission is revoked
        // later), keep the not-yet-granted one at full brightness.
        if (mExactButton != null) {
            mExactButton.setAlpha(exactOk ? 0.45f : 1.0f);
        }
        if (mBatteryButton != null) {
            mBatteryButton.setAlpha(batteryOk ? 0.45f : 1.0f);
        }
        if (mSoundTitle != null) {
            mSoundTitle.setText(AlarmStore.getBackupSoundTitle(this));
        }
        if (mLastOutcome != null) {
            mLastOutcome.setText("Last backup: " + AlarmStore.getLastBackupOutcome(this));
        }
    }

    // ------------------------------------------------------------------ setup

    private void openExactAlarmSettings() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null && am.canScheduleExactAlarms()) {
            Toast.makeText(this, "Exact alarms already allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
        }
    }

    private void openBatterySettings() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Already exempt from battery optimisation",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /**
     * The direct band link needs BLUETOOTH_CONNECT on Android 12+
     * (shown as "Nearby devices"); on Android 10/11, BLE operations
     * still ride on the location permission. Without one of these,
     * band syncs fail with an explanatory toast from the transport.
     */
    private void requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 3);
        }
    }

    /** Opens the system ringtone picker, exactly like the Clock app does. */
    private void openSoundPicker() {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Sound");
        Uri current = AlarmStore.getEffectiveBackupSoundUri(this);
        if (current != null) {
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current);
        }
        startActivityForResult(intent, REQUEST_PICK_BACKUP_SOUND);
    }

    /** Plays the selected sound for about 3 seconds, then stops it. */
    private void playTestSound() {
        stopTestSound();
        Uri uri = AlarmStore.getEffectiveBackupSoundUri(this);
        if (uri == null) {
            Toast.makeText(this, "No sound available", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            mTestPlayer = new MediaPlayer();
            mTestPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mTestPlayer.setDataSource(this, uri);
            mTestPlayer.setLooping(false);
            mTestPlayer.prepare();
            mTestPlayer.start();
            mTestHandler.postDelayed(mTestStopRunnable, TEST_SOUND_MS);
        } catch (Exception e) {
            stopTestSound();
            Toast.makeText(this, "Could not play sound", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Playing sound", Toast.LENGTH_SHORT).show();
    }

    private void stopTestSound() {
        mTestHandler.removeCallbacks(mTestStopRunnable);
        if (mTestPlayer != null) {
            try {
                if (mTestPlayer.isPlaying()) {
                    mTestPlayer.stop();
                }
            } catch (IllegalStateException ignored) {
                // The player may already be in an error or released state.
            }
            mTestPlayer.release();
            mTestPlayer = null;
        }
    }

    /**
     * The small battery glyph in the band status row, drawn to match
     * the render approved for 1.7: a rounded-rect outline with a
     * terminal nub on the right and an inner fill whose width tracks
     * the stored level. A negative level (never read) draws the
     * outline with an empty fill.
     */
    private static class BatteryGlyphView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mLevel = -1;

        BatteryGlyphView(Context context) {
            super(context);
        }

        void setLevel(int level) {
            if (mLevel != level) {
                mLevel = level;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            float density = getResources().getDisplayMetrics().density;
            float stroke = Math.max(1f, 1.1f * density);
            float nubWidth = 2f * density;
            float nubGap = 1.5f * density;
            float bodyRight = width - nubWidth - nubGap;
            float halfStroke = stroke / 2f;
            float radius = height * 0.28f;

            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(stroke);
            mPaint.setColor(COLOR_TEXT_DIM);
            canvas.drawRoundRect(new RectF(halfStroke, halfStroke,
                    bodyRight - halfStroke, height - halfStroke), radius, radius, mPaint);

            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(COLOR_TEXT_DIM);
            float nubHeight = height * 0.42f;
            canvas.drawRoundRect(new RectF(bodyRight + nubGap * 0.4f,
                    (height - nubHeight) / 2f, width,
                    (height + nubHeight) / 2f), density, density, mPaint);

            if (mLevel > 0) {
                float pad = stroke + 1.2f * density;
                float fillRight = pad + (bodyRight - 2f * pad)
                        * (Math.min(mLevel, 100) / 100f);
                if (fillRight > pad && height - pad > pad) {
                    mPaint.setColor(COLOR_ACCENT_LIGHT);
                    float fillRadius = (height - 2f * pad) * 0.32f;
                    canvas.drawRoundRect(new RectF(pad, pad, fillRight,
                            height - pad), fillRadius, fillRadius, mPaint);
                }
            }
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
