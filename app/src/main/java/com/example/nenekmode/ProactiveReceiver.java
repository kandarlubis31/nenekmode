package com.example.nenekmode;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Asisten proaktif: nyapa duluan di jam-jam tertentu (default 09:30 & 15:30)
 * biar terasa seperti teman yang inisiatif, bukan cuma nunggu dipanggil.
 * requestCode 700; menjadwalkan ulang dirinya setiap kali mati.
 */
public class ProactiveReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "proactive_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("companion_enabled", false)) return; // fitur dimatikan
        if (ConversationEngine.isNightMode(context)) { // mode malam: jangan ganggu
            rescheduleNext(context);
            return;
        }

        String elderName = prefs.getString("nenek_name", "Bu");
        String assistantName = prefs.getString("assistant_name", "Kandar");

        String tod = ConversationEngine.getTimeOfDay();
        String line = GreetingBank.greeting(tod, elderName, assistantName);

        // variasi harian: selasa & kamis ajak kuis, rabu & jumat ajak nostalgia
        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.TUESDAY || dow == Calendar.THURSDAY) {
            line += " Kalau mau, bilang aja 'mulai kuis'.";
        } else if (dow == Calendar.WEDNESDAY || dow == Calendar.FRIDAY) {
            line += " Kalau mau, kita cerita zaman dulu aja. Bilang aja 'cerita dulu'.";
        }

        // peringatan cuaca pagi (opsional)
        if ("pagi".equals(tod) && prefs.getBoolean("weather_alert_enabled", false)) {
            try {
                double lat = prefs.getFloat("last_lat", -6.2f);
                double lon = prefs.getFloat("last_lon", 106.8f);
                String warn = WeatherClient.getWeatherWarningSync(lat, lon);
                if (!warn.isEmpty()) line += " " + warn;
            } catch (Exception ignored) {}
        }

        // berita pagi: kalau pagi hari & diaktifkan, sertakan headline berita
        if ("pagi".equals(tod) && prefs.getBoolean("news_enabled", true)) {
            try {
                java.util.List<String> headlines = new NewsClient().getTopHeadlinesSync();
                if (!headlines.isEmpty()) {
                    StringBuilder sb = new StringBuilder(line + " Ini ada berita pagi. ");
                    int n = Math.min(3, headlines.size());
                    for (int i = 0; i < n; i++) {
                        sb.append("Berita ").append(i + 1).append(": ")
                          .append(headlines.get(i)).append(". ");
                    }
                    line = sb.toString();
                }
            } catch (Exception ignored) {}
        }

        SpeechUtil.speak(context, line);

        // notifikasi ringan sebagai visual
        NotificationManagerHelper.notifyProactive(context, elderName, line);

        // jadwalkan ulang ke slot proaktif berikutnya
        rescheduleNext(context);
    }

    public static void rescheduleNext(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        try {
            JSONArray times = new JSONArray(prefs.getString("proactive_times",
                    "[{\"h\":9,\"m\":30},{\"h\":15,\"m\":30}]"));
            long best = -1;
            for (int i = 0; i < times.length(); i++) {
                JSONObject t = times.getJSONObject(i);
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, t.getInt("h"));
                cal.set(Calendar.MINUTE, t.getInt("m"));
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_MONTH, 1);
                }
                if (best < 0 || cal.getTimeInMillis() < best) best = cal.getTimeInMillis();
            }
            if (best > 0) {
                Intent i = new Intent(context, ProactiveReceiver.class);
                PendingIntent pi = PendingIntent.getBroadcast(context, 700, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                AlarmScheduler.scheduleExact(context, best, pi);
            }
        } catch (Exception ignored) {}
    }
}
