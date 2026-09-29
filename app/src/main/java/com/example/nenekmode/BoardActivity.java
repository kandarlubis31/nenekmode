package com.example.nenekmode;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Mode papan info: HP diparkir jadi "jam meja pintar" — layar tetap nyala
 * (kecerahan rendah biar hemat), menampilkan jam besar, tanggal,
 * sholat & obat berikutnya, cuaca singkat, status Mode Teman, dan baterai.
 * Toggle via tombol "Papan Info" di MainActivity; tetap aktif saat reboot.
 */
public class BoardActivity extends Activity {

    private TextView clockText, dateText, nextPrayerText, nextMedText,
            weatherText, companionText, batteryText, hintTitle;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private final WeatherClient weatherClient = new WeatherClient();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 30 * 1000L);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                batteryText.setText("Baterai " + (level * 100 / scale) + "%");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        if (getIntent().getBooleanExtra("toggle", false)) {
            prefs.edit().putBoolean("board_mode", !prefs.getBoolean("board_mode", false)).apply();
        }
        if (!prefs.getBoolean("board_mode", false)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_board);

        clockText = findViewById(R.id.boardClock);
        dateText = findViewById(R.id.boardDate);
        nextPrayerText = findViewById(R.id.boardNextPrayer);
        nextMedText = findViewById(R.id.boardNextMed);
        weatherText = findViewById(R.id.boardWeather);
        companionText = findViewById(R.id.boardCompanion);
        batteryText = findViewById(R.id.boardBattery);
        hintTitle = findViewById(R.id.boardHint);

        // layar tetap nyala tapi redup (hemat baterai & nyaman di kamar)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.02f;
        getWindow().setAttributes(lp);

        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        weatherText.setOnClickListener(v -> loadWeather());
        hintTitle.setOnClickListener(v -> backToMain());
    }

    private void backToMain() {
        prefs.edit().putBoolean("board_mode", false).apply();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        handler.postDelayed(tick, 30 * 1000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {}
    }

    private void refresh() {
        Calendar c = Calendar.getInstance();
        clockText.setText(new SimpleDateFormat("HH:mm", Locale.US).format(c.getTime()));
        dateText.setText(new SimpleDateFormat("EEEE, dd MMMM yyyy", new Locale("id", "ID")).format(new Date()));

        nextPrayerText.setText(PrayerTimes.nextPrayerShort(this));
        nextMedText.setText(nextMedicineText());

        boolean companion = prefs.getBoolean("keep_alive", false);
        companionText.setText("Mode Teman: " + (companion ? "AKTIF ✅" : "MATI ❌"));
        // watchdog kecil versi UI: kalau seharusnya aktif tapi service mati, nyalakan
        if (companion && !CompanionService.isRunning) {
            CompanionService.start(this);
        }

        loadWeather();
    }

    private String nextMedicineText() {
        org.json.JSONArray times = ReminderReceiver.getMedTimes(prefs);
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int best = -1, bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < times.length(); i++) {
            try {
                org.json.JSONObject t = times.getJSONObject(i);
                if (!t.optBoolean("en", true)) continue;
                int m = t.getInt("h") * 60 + t.getInt("m");
                int diff = (m >= nowMin) ? (m - nowMin) : (m + 24 * 60 - nowMin);
                if (diff < bestDiff) { bestDiff = diff; best = m; }
            } catch (Exception ignored) {}
        }
        if (best < 0) return "Obat berikutnya: belum diatur";
        if (bestDiff == 0) return "Sekarang waktunya minum obat!";
        String when = bestDiff >= 60
                ? (bestDiff / 60) + " jam " + (bestDiff % 60) + " menit"
                : bestDiff + " menit";
        return "Obat berikutnya " + String.format(Locale.US, "%02d:%02d", best / 60, best % 60)
                + " (" + when + " lagi)";
    }

    private void loadWeather() {
        float lat = prefs.getFloat("last_lat", -6.2f);
        float lon = prefs.getFloat("last_lon", 106.8f);
        weatherClient.getCurrentWeather(lat, lon, new WeatherClient.WeatherCallback() {
            @Override public void onSuccess(String response) {
                runOnUiThread(() -> weatherText.setText(response));
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> weatherText.setText("Cuaca: tidak tersedia (cek internet)"));
            }
        });
    }
}
