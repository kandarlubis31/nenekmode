package com.example.nenekmode;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Pengingat minum air (opsional, toggle di settings).
 * Interval bisa diatur (default 2 jam). Hening saat mode malam.
 * requestCode 1200.
 */
public class WaterReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("water_enabled", false)) return;

        // jangan ganggu saat mode malam
        if (!ConversationEngine.isNightMode(context)) {
            String elderName = prefs.getString("nenek_name", "Bu");
            String line = elderName + ", yuk minum dulu segelas air ya. Biar badannya segar.";
            SpeechUtil.speak(context, line);
            NotificationManagerHelper.notifyProactive(context, "Waktu Minum", line);
            prefs.edit().putBoolean("water_confirm_waiting", true).apply();
        }

        // jadwalkan ulang
        scheduleNext(context);
    }

    public static void scheduleNext(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("water_enabled", false)) return;
        long intervalMs = prefs.getInt("water_interval_min", 120) * 60 * 1000L;
        Intent i = new Intent(context, WaterReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, 1200, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmScheduler.scheduleExact(context, System.currentTimeMillis() + intervalMs, pi);
    }

    public static void cancel(Context context) {
        android.app.AlarmManager am =
                (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, WaterReceiver.class);
        am.cancel(PendingIntent.getBroadcast(context, 1200, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
}
