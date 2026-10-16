package com.example.nenekmode;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Pengingat minum obat multi-jadwal.
 * Jadwal disimpan di prefs "med_times": [{"h":8,"m":0,"en":true}, ...]
 *requestCode alarm:
 *  - 100+index : alarm minum obat harian
 *  - 500       : snooze 10 menit
 *  - 900+index : konfirmasi 15 menit setelah pengingat
 * Setiap alarm yang mati akan menjadwalkan ulang kejadian berikutnya sendiri,
 * jadi tidak bergantung pada app yang dibuka.
 */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "reminder_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        createNotificationChannel(context);
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        String elderName = prefs.getString("nenek_name", "Bu");

        int index = intent.getIntExtra("index", 0);
        JSONArray times = getMedTimes(prefs);
        int h = 8, m = 0;
        try {
            JSONObject t = times.getJSONObject(index);
            h = t.getInt("h");
            m = t.getInt("m");
        } catch (Exception ignored) {}

        String msg = prefs.getString("reminder_message", "Sudah waktunya minum obat ya!");
        String text = msg + " (" + String.format("%02d:%02d", h, m) + ") - " + elderName;

        // notifikasi
        Intent notificationIntent = new Intent(context, com.getcapacitor.CapacitorActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, index, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new android.app.Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle("Pengingat Obat")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setPriority(android.app.Notification.PRIORITY_HIGH);
        } else {
            builder = new android.app.Notification.Builder(context)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle("Pengingat Obat")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setPriority(android.app.Notification.PRIORITY_HIGH);
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(100 + index, builder.build());

        // suara (variasi hangat, jangan monoton "Nama, pesan")
        String tod = ConversationEngine.getTimeOfDay();
        String greet = GreetingBank.greeting(tod, elderName, prefs.getString("assistant_name", "Kandar"));
        SpeechUtil.speak(context, greet + " Oh iya, " + msg);

        // aktifkan alur konfirmasi suara + jadwalkan follow-up 15 menit
        prefs.edit().putBoolean("med_confirm_waiting", true).apply();
        Intent confirmIntent = new Intent(context, MedConfirmReceiver.class);
        confirmIntent.putExtra("index", index);
        PendingIntent confirmPi = PendingIntent.getBroadcast(context, 900 + index, confirmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmScheduler.scheduleExact(context, System.currentTimeMillis() + 15 * 60 * 1000L, confirmPi);
    }

    // ================= Penjadwalan =================

    /** Jadwalkan semua slot obat yang aktif ke kejadian terdekat berikutnya. */
    public static void setReminders(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        JSONArray times = getMedTimes(prefs);
        for (int i = 0; i < times.length(); i++) {
            try {
                JSONObject t = times.getJSONObject(i);
                if (!t.optBoolean("en", true)) continue;
                scheduleSlot(context, i, t.getInt("h"), t.getInt("m"));
            } catch (Exception ignored) {}
        }
    }

    public static void scheduleSlot(Context context, int index, int hour, int minute) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra("index", index);
        PendingIntent pi = PendingIntent.getBroadcast(context, 100 + index, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        AlarmScheduler.scheduleExact(context, cal.getTimeInMillis(), pi);
    }

    public static void cancelReminders(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        JSONArray times = getMedTimes(prefs);
        for (int i = 0; i < Math.max(times.length(), 3); i++) {
            Intent intent = new Intent(context, ReminderReceiver.class);
            am.cancel(PendingIntent.getBroadcast(context, 100 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            Intent confirmIntent = new Intent(context, MedConfirmReceiver.class);
            am.cancel(PendingIntent.getBroadcast(context, 900 + i, confirmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        Intent snooze = new Intent(context, ReminderReceiver.class);
        am.cancel(PendingIntent.getBroadcast(context, 500, snooze,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    /** Tunda 10 menit (dipakai saat nenek bilang "belum"). */
    public static void snoozeTenMinutes(Context context) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra("index", 0);
        PendingIntent pi = PendingIntent.getBroadcast(context, 500, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmScheduler.scheduleExact(context, System.currentTimeMillis() + 10 * 60 * 1000L, pi);
    }

    // ================= Penyimpanan jadwal =================

    public static JSONArray getMedTimes(SharedPreferences prefs) {
        try {
            return new JSONArray(prefs.getString("med_times", defaultMedTimes()));
        } catch (Exception e) {
            try {
                return new JSONArray(defaultMedTimes());
            } catch (Exception ignored) {
                return new JSONArray();
            }
        }
    }

    public static String defaultMedTimes() {
        try {
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("h", 8).put("m", 0).put("en", true));
            arr.put(new JSONObject().put("h", 14).put("m", 0).put("en", false));
            arr.put(new JSONObject().put("h", 20).put("m", 0).put("en", false));
            return arr.toString();
        } catch (Exception ignored) {
            return "[]";
        }
    }

    public static void createNotificationChannel(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Pengingat Obat", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifikasi pengingat minum obat");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
