package com.example.nenekmode;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Sapaan spesial di hari ulang tahun / tanggal penting (opsional).
 * Tanggal disimpan "MM-dd" di prefs birthday_list (JSON array).
 * requestCode 1300 (adzan-style: dijadwalkan ulang tiap hari 00:05).
 */
public class BirthdayReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("birthday_enabled", false)) {
            scheduleNext(context);
            return;
        }

        String today = new SimpleDateFormat("MM-dd", Locale.US).format(new Date());
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("birthday_list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String date = arr.optString(i, "");
                if (!date.equals(today)) continue;
                String elderName = prefs.getString("nenek_name", "Bu");
                String assistantName = prefs.getString("assistant_name", "Kandar");
                String line;
                if ("12-31".equals(date) || "01-01".equals(date)) {
                    line = "Selamat tahun baru " + elderName + "! Semoga tahun ini penuh kesehatan dan kebahagiaan ya.";
                } else {
                    line = "Selamat ulang tahun " + elderName + "! Semoga Tuhan selalu memberi kesehatan, kebahagiaan, dan panjang umur. "
                            + assistantName + " ikut berbahagia hari ini.";
                }
                SpeechUtil.speak(context, line);
                NotificationManagerHelper.notifyProactive(context, "Hari Spesial", line);
            }
        } catch (Exception ignored) {}

        scheduleNext(context);
    }

    /** Cek ulang besok jam 00:05. */
    public static void scheduleNext(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("birthday_enabled", false)) return;
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 5);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Intent i = new Intent(context, BirthdayReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, 1300, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmScheduler.scheduleExact(context, cal.getTimeInMillis(), pi);
    }

    public static void cancel(Context context) {
        android.app.AlarmManager am =
                (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, BirthdayReceiver.class);
        am.cancel(PendingIntent.getBroadcast(context, 1300, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
}
