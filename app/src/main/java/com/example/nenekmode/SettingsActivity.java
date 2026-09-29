package com.example.nenekmode;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

public class SettingsActivity extends AppCompatActivity {

    private EditText nenekNameInput;
    private EditText assistantNameInput;
    private EditText emergencyNumberInput;
    private EditText favoriteContactNameInput;
    private EditText favoriteContactNumberInput;
    private Spinner fontSizeSpinner;
    private Switch highContrastSwitch;
    private EditText medHour1, medMin1, medHour2, medMin2, medHour3, medMin3;
    private Switch medSwitch1, medSwitch2, medSwitch3;
    private EditText reminderMessageInput;
    private Switch reminderSwitch;
    private EditText checkInHourInput;
    private EditText checkInMinInput;
    private Switch checkInSwitch;
    private Switch escalationSwitch;
    private Switch keepAliveSwitch;
    private Switch proactiveSwitch;
    private Switch wakeSwitch;
    private Switch newsSwitch;
    private Switch adzanSwitch;
    private Switch waterSwitch;
    private EditText waterIntervalInput;
    private Switch storiesSwitch;
    private Switch weatherAlertSwitch;
    private Switch bpSwitch;
    private Switch birthdaySwitch;
    private EditText birthdayDateInput;
    private Switch nightModeSwitch;
    private EditText nightStartInput;
    private EditText nightEndInput;
    private Switch byokSwitch;
    private EditText byokKeyInput;
    private Spinner modelSpinner;
    private Button testKeyButton;
    private Switch voskSwitch;
    private EditText voskUrlInput;
    private Button downloadModelButton;
    private Button backupButton;
    private Button restoreButton;
    private LinearLayout catUmum;
    private LinearLayout catTeknis;
    private Button tabUmum;
    private Button tabTeknis;

    private static final String[] MODEL_IDS = {
            "llama-3.1-8b-instant", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"
    };
    private Button saveButton;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        nenekNameInput = findViewById(R.id.nenekNameInput);
        assistantNameInput = findViewById(R.id.assistantNameInput);
        emergencyNumberInput = findViewById(R.id.emergencyNumberInput);
        favoriteContactNameInput = findViewById(R.id.favoriteContactNameInput);
        favoriteContactNumberInput = findViewById(R.id.favoriteContactNumberInput);
        fontSizeSpinner = findViewById(R.id.fontSizeSpinner);
        highContrastSwitch = findViewById(R.id.highContrastSwitch);
        medHour1 = findViewById(R.id.medHour1);
        medMin1 = findViewById(R.id.medMin1);
        medHour2 = findViewById(R.id.medHour2);
        medMin2 = findViewById(R.id.medMin2);
        medHour3 = findViewById(R.id.medHour3);
        medMin3 = findViewById(R.id.medMin3);
        medSwitch1 = findViewById(R.id.medSwitch1);
        medSwitch2 = findViewById(R.id.medSwitch2);
        medSwitch3 = findViewById(R.id.medSwitch3);
        reminderMessageInput = findViewById(R.id.reminderMessageInput);
        reminderSwitch = findViewById(R.id.reminderSwitch);
        checkInHourInput = findViewById(R.id.checkInHourInput);
        checkInMinInput = findViewById(R.id.checkInMinInput);
        checkInSwitch = findViewById(R.id.checkInSwitch);
        escalationSwitch = findViewById(R.id.escalationSwitch);
        keepAliveSwitch = findViewById(R.id.keepAliveSwitch);
        proactiveSwitch = findViewById(R.id.proactiveSwitch);
        wakeSwitch = findViewById(R.id.wakeSwitch);
        newsSwitch = findViewById(R.id.newsSwitch);
        adzanSwitch = findViewById(R.id.adzanSwitch);
        waterSwitch = findViewById(R.id.waterSwitch);
        waterIntervalInput = findViewById(R.id.waterIntervalInput);
        storiesSwitch = findViewById(R.id.storiesSwitch);
        weatherAlertSwitch = findViewById(R.id.weatherAlertSwitch);
        bpSwitch = findViewById(R.id.bpSwitch);
        birthdaySwitch = findViewById(R.id.birthdaySwitch);
        birthdayDateInput = findViewById(R.id.birthdayDateInput);
        nightModeSwitch = findViewById(R.id.nightModeSwitch);
        nightStartInput = findViewById(R.id.nightStartInput);
        nightEndInput = findViewById(R.id.nightEndInput);
        byokSwitch = findViewById(R.id.byokSwitch);
        byokKeyInput = findViewById(R.id.byokKeyInput);
        modelSpinner = findViewById(R.id.modelSpinner);
        testKeyButton = findViewById(R.id.testKeyButton);
        voskSwitch = findViewById(R.id.voskSwitch);
        voskUrlInput = findViewById(R.id.voskUrlInput);
        downloadModelButton = findViewById(R.id.downloadModelButton);
        backupButton = findViewById(R.id.backupButton);
        restoreButton = findViewById(R.id.restoreButton);
        catUmum = findViewById(R.id.catUmum);
        catTeknis = findViewById(R.id.catTeknis);
        tabUmum = findViewById(R.id.tabUmum);
        tabTeknis = findViewById(R.id.tabTeknis);
        saveButton = findViewById(R.id.saveButton);

        prefs = getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);

        String[] fontSizes = {"Normal", "Besar", "Sangat Besar"};
        fontSizeSpinner.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, fontSizes));

        String[] modelLabels = {
                "Cepat (Llama 3.1 8B)",
                "Pintar (Llama 3.3 70B)",
                "Hemat (GPT-OSS 20B)"
        };
        modelSpinner.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, modelLabels));

        loadSettings();

        saveButton.setOnClickListener(v -> saveSettings());
        backupButton.setOnClickListener(v -> BackupUtil.backup(this));
        restoreButton.setOnClickListener(v -> BackupUtil.restore(this));

        setupTabs();
    }

    /** Tab kategori: Umum = yang penting buat keluarga, Teknis = sisanya. */
    private void setupTabs() {
        tabUmum.setOnClickListener(v -> selectTab(true));
        tabTeknis.setOnClickListener(v -> selectTab(false));
        selectTab(true); // default buka Umum
    }

    private void selectTab(boolean umum) {
        catUmum.setVisibility(umum ? View.VISIBLE : View.GONE);
        catTeknis.setVisibility(umum ? View.GONE : View.VISIBLE);
        tabUmum.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, umum ? R.color.baachan_primary : R.color.gray)));
        tabTeknis.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, umum ? R.color.gray : R.color.baachan_primary)));
    }

    private void loadSettings() {
        nenekNameInput.setText(prefs.getString("nenek_name", "Bu"));
        assistantNameInput.setText(prefs.getString("assistant_name", "Kandar"));
        emergencyNumberInput.setText(prefs.getString("emergency_number", ""));
        favoriteContactNameInput.setText(prefs.getString("favorite_contact_name", ""));
        favoriteContactNumberInput.setText(prefs.getString("favorite_contact_number", ""));

        int fontSize = prefs.getInt("font_size", 1);
        fontSizeSpinner.setSelection(fontSize);

        highContrastSwitch.setChecked(prefs.getBoolean("high_contrast", false));

        // jadwal obat multi-slot
        JSONArray times = ReminderReceiver.getMedTimes(prefs);
        EditText[] hours = {medHour1, medHour2, medHour3};
        EditText[] mins = {medMin1, medMin2, medMin3};
        Switch[] switches = {medSwitch1, medSwitch2, medSwitch3};
        boolean anyEnabled = false;
        for (int i = 0; i < 3; i++) {
            try {
                JSONObject t = times.getJSONObject(i);
                hours[i].setText(String.valueOf(t.getInt("h")));
                mins[i].setText(String.valueOf(t.getInt("m")));
                boolean en = t.optBoolean("en", true);
                switches[i].setChecked(en);
                if (en) anyEnabled = true;
            } catch (Exception e) {
                hours[i].setText(i == 0 ? "8" : (i == 1 ? "14" : "20"));
                mins[i].setText("0");
                switches[i].setChecked(false);
            }
        }
        reminderSwitch.setChecked(anyEnabled);
        reminderMessageInput.setText(prefs.getString("reminder_message", "Waktu minum obat ya!"));

        checkInHourInput.setText(String.valueOf(prefs.getInt("checkin_hour", 9)));
        checkInMinInput.setText(String.valueOf(prefs.getInt("checkin_minute", 0)));
        checkInSwitch.setChecked(prefs.getBoolean("checkin_enabled", false));
        escalationSwitch.setChecked(prefs.getBoolean("escalation_enabled", true));

        keepAliveSwitch.setChecked(prefs.getBoolean("keep_alive", false));
        proactiveSwitch.setChecked(prefs.getBoolean("companion_enabled", true));
        wakeSwitch.setChecked("on".equals(prefs.getString("wake_mode", "off")));
        newsSwitch.setChecked(prefs.getBoolean("news_enabled", true));
        adzanSwitch.setChecked(prefs.getBoolean("adzan_enabled", true));

        // fitur opsional
        waterSwitch.setChecked(prefs.getBoolean("water_enabled", false));
        waterIntervalInput.setText(String.valueOf(prefs.getInt("water_interval_min", 120)));
        storiesSwitch.setChecked(prefs.getBoolean("stories_enabled", false));
        weatherAlertSwitch.setChecked(prefs.getBoolean("weather_alert_enabled", false));
        bpSwitch.setChecked(prefs.getBoolean("bp_enabled", false));
        birthdaySwitch.setChecked(prefs.getBoolean("birthday_enabled", false));
        StringBuilder dates = new StringBuilder();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("birthday_list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                if (dates.length() > 0) dates.append(", ");
                dates.append(arr.optString(i));
            }
        } catch (Exception ignored) {}
        birthdayDateInput.setText(dates.toString());
        nightModeSwitch.setChecked(prefs.getBoolean("night_mode_enabled", false));
        nightStartInput.setText(String.valueOf(prefs.getInt("night_start_hour", 21)));
        nightEndInput.setText(String.valueOf(prefs.getInt("night_end_hour", 5)));

        // BYOK
        byokSwitch.setChecked(prefs.getBoolean("byok_enabled", false));
        byokKeyInput.setText(prefs.getString("byok_api_key", ""));
        String savedModel = prefs.getString("ai_model", MODEL_IDS[0]);
        int modelIdx = 0;
        for (int i = 0; i < MODEL_IDS.length; i++) {
            if (MODEL_IDS[i].equals(savedModel)) { modelIdx = i; break; }
        }
        modelSpinner.setSelection(modelIdx);

        testKeyButton.setOnClickListener(v -> testKey());

        // wake word offline (Vosk)
        voskSwitch.setChecked(prefs.getBoolean("vosk_enabled", false));
        voskUrlInput.setText(WakeWordManager.getModelUrl(this));
        downloadModelButton.setOnClickListener(v -> downloadVoskModel());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BackupUtil.REQUEST_BACKUP) {
            BackupUtil.onBackupResult(this, resultCode, data);
        } else if (requestCode == BackupUtil.REQUEST_RESTORE) {
            BackupUtil.onRestoreResult(this, resultCode, data);
        }
    }

    private void downloadVoskModel() {
        String url = voskUrlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "Isi dulu URL model Vosk (.zip)", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString("vosk_model_url", url)
                .putBoolean("vosk_enabled", voskSwitch.isChecked()).apply();
        Toast.makeText(this, "Mengunduh model (bisa 40-60 MB), lihat notifikasi...", Toast.LENGTH_LONG).show();
        new WakeWordManager(this).ensureReady((ok, msg) -> runOnUiThread(() -> {
            if (ok) {
                Toast.makeText(this, "Model siap! Wake word offline aktif.", Toast.LENGTH_LONG).show();
            } else if ("loading".equals(msg)) {
                Toast.makeText(this, "Sedang diproses...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Gagal: " + msg, Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void testKey() {
        String key = byokKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            key = GroqClient.resolveApiKey(this); // fallback: key bawaan
        }
        final String effectiveKey = key;
        Toast.makeText(this, "Menguji koneksi...", Toast.LENGTH_SHORT).show();
        GroqClient.testApiKey(this, effectiveKey, (ok, message) ->
                runOnUiThread(() -> Toast.makeText(this, message,
                        ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show()));
    }

    private void saveSettings() {
        String nenekName = nenekNameInput.getText().toString().trim();
        String assistantName = assistantNameInput.getText().toString().trim();
        String emergencyNumber = emergencyNumberInput.getText().toString().trim();
        String favoriteContactName = favoriteContactNameInput.getText().toString().trim();
        String favoriteContactNumber = favoriteContactNumberInput.getText().toString().trim();

        if (nenekName.isEmpty() || assistantName.isEmpty()) {
            Toast.makeText(this, "Nama tidak boleh kosong", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!emergencyNumber.isEmpty() && !isValidPhoneNumber(emergencyNumber)) {
            Toast.makeText(this, "Nomor telepon darurat tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!favoriteContactNumber.isEmpty() && !isValidPhoneNumber(favoriteContactNumber)) {
            Toast.makeText(this, "Nomor kontak favorit tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("nenek_name", nenekName);
        editor.putString("assistant_name", assistantName);
        editor.putString("emergency_number", emergencyNumber);
        editor.putString("favorite_contact_name", favoriteContactName);
        editor.putString("favorite_contact_number", favoriteContactNumber);
        editor.putInt("font_size", fontSizeSpinner.getSelectedItemPosition());
        editor.putBoolean("high_contrast", highContrastSwitch.isChecked());
        editor.putString("reminder_message", reminderMessageInput.getText().toString().trim());

        // jadwal obat multi-slot
        try {
            JSONArray arr = new JSONArray();
            EditText[] hours = {medHour1, medHour2, medHour3};
            EditText[] mins = {medMin1, medMin2, medMin3};
            Switch[] switches = {medSwitch1, medSwitch2, medSwitch3};
            boolean anyEnabled = false;
            for (int i = 0; i < 3; i++) {
                int h = clamp(parseTimeInt(hours[i], i == 0 ? 8 : (i == 1 ? 14 : 20)), 0, 23);
                int m = clamp(parseTimeInt(mins[i], 0), 0, 59);
                boolean en = switches[i].isChecked();
                if (en) anyEnabled = true;
                JSONObject t = new JSONObject();
                t.put("h", h);
                t.put("m", m);
                t.put("en", en);
                arr.put(t);
            }
            editor.putString("med_times", arr.toString());
            editor.putBoolean("reminder_enabled", anyEnabled);
        } catch (Exception ignored) {}

        // check-in
        editor.putInt("checkin_hour", clamp(parseTimeInt(checkInHourInput, 9), 0, 23));
        editor.putInt("checkin_minute", clamp(parseTimeInt(checkInMinInput, 0), 0, 59));
        editor.putBoolean("checkin_enabled", checkInSwitch.isChecked());
        editor.putBoolean("escalation_enabled", escalationSwitch.isChecked());

        // mode teman
        boolean keepAlive = keepAliveSwitch.isChecked();
        editor.putBoolean("keep_alive", keepAlive);
        editor.putBoolean("companion_enabled", proactiveSwitch.isChecked());
        editor.putString("wake_mode", wakeSwitch.isChecked() ? "on" : "off");
        editor.putBoolean("news_enabled", newsSwitch.isChecked());
        boolean adzanEnabled = adzanSwitch.isChecked();
        editor.putBoolean("adzan_enabled", adzanEnabled);

        // fitur opsional
        boolean waterEnabled = waterSwitch.isChecked();
        editor.putBoolean("water_enabled", waterEnabled);
        int waterInterval = clamp(parseTimeInt(waterIntervalInput, 120), 15, 720);
        editor.putInt("water_interval_min", waterInterval);
        editor.putBoolean("stories_enabled", storiesSwitch.isChecked());
        editor.putBoolean("weather_alert_enabled", weatherAlertSwitch.isChecked());
        editor.putBoolean("bp_enabled", bpSwitch.isChecked());
        boolean birthdayEnabled = birthdaySwitch.isChecked();
        editor.putBoolean("birthday_enabled", birthdayEnabled);
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String d : birthdayDateInput.getText().toString().split(",")) {
                String s = d.trim();
                if (s.matches("^(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$")) {
                    arr.put(s);
                }
            }
            editor.putString("birthday_list", arr.toString());
        } catch (Exception ignored) {}
        editor.putBoolean("night_mode_enabled", nightModeSwitch.isChecked());
        editor.putInt("night_start_hour", clamp(parseTimeInt(nightStartInput, 21), 0, 23));
        editor.putInt("night_end_hour", clamp(parseTimeInt(nightEndInput, 5), 0, 23));

        // BYOK
        editor.putBoolean("byok_enabled", byokSwitch.isChecked());
        editor.putString("byok_api_key", byokKeyInput.getText().toString().trim());
        editor.putBoolean("vosk_enabled", voskSwitch.isChecked());
        editor.putString("vosk_model_url", voskUrlInput.getText().toString().trim());
        editor.putString("ai_model", MODEL_IDS[Math.max(0,
                Math.min(MODEL_IDS.length - 1, modelSpinner.getSelectedItemPosition()))]);
        editor.apply();

        // terapkan semua alarm
        ReminderReceiver.cancelReminders(this);
        CheckInReceiver.cancelCheckIn(this);
        if (reminderSwitch.isChecked()) {
            ReminderReceiver.setReminders(this);
        }
        if (checkInSwitch.isChecked()) {
            CheckInReceiver.setCheckIn(this, clamp(parseTimeInt(checkInHourInput, 9), 0, 23),
                    clamp(parseTimeInt(checkInMinInput, 0), 0, 59));
        }
        ProactiveReceiver.rescheduleNext(this);

        // jadwal adzan
        if (adzanEnabled) {
            PrayerTimes.refreshIfNeededAsync(this, null);
        } else {
            PrayerTimes.cancelAll(this);
        }

        // fitur opsional
        if (waterEnabled) {
            WaterReceiver.scheduleNext(this);
        } else {
            WaterReceiver.cancel(this);
        }
        if (birthdayEnabled) {
            BirthdayReceiver.scheduleNext(this);
        } else {
            BirthdayReceiver.cancel(this);
        }

        // service keep-alive
        if (keepAlive) {
            CompanionService.start(this);
        } else if (CompanionService.isRunning) {
            CompanionService.stop(this);
        }

        Toast.makeText(this, "Pengaturan tersimpan!", Toast.LENGTH_SHORT).show();
        finish();
    }

    private int parseTimeInt(EditText input, int fallback) {
        try {
            String s = input.getText().toString().trim();
            if (s.isEmpty()) return fallback;
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private boolean isValidPhoneNumber(String phone) {
        phone = phone.replaceAll("[^0-9+]", "");
        return (phone.startsWith("0") || phone.startsWith("+62")) && phone.length() >= 10;
    }
}
