package com.example.nenekmode;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/**
 * Alarm cek harian: bertanya kabar lewat suara, lalu menjadwalkan ulang dirinya.
 */
public class CheckInReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "checkin_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        createNotificationChannel(context);
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        String elderName = prefs.getString("nenek_name", "Bu");

        String question = "Halo! Bagaimana kesehatan " + elderName + " hari ini?";
        SpeechUtil.speak(context, question + " Bilang aja kalau mau cerita.");

        // jaring pengaman: kalau 30 menit tak ada respons -> SMS ke keluarga
        EscalationManager.arm(context);

        Intent notificationIntent = new Intent(context, com.getcapacitor.CapacitorActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new android.app.Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle("Cek Harian")
                    .setContentText(question)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setPriority(android.app.Notification.PRIORITY_DEFAULT);
        } else {
            builder = new android.app.Notification.Builder(context)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle("Cek Harian")
                    .setContentText(question)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setPriority(android.app.Notification.PRIORITY_DEFAULT);
        }

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(2, builder.build());

        // jadwalkan ulang untuk besok
        setCheckIn(context, prefs.getInt("checkin_hour", 9), prefs.getInt("checkin_minute", 0));
    }

    public static void setCheckIn(Context context, int hour, int minute) {
        Intent intent = new Intent(context, CheckInReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        AlarmScheduler.scheduleExact(context, calendar.getTimeInMillis(), pendingIntent);
    }

    public static void cancelCheckIn(Context context) {
        AlarmManagerSafe(context);
    }

    private static void AlarmManagerSafe(Context context) {
        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, CheckInReceiver.class);
        am.cancel(PendingIntent.getBroadcast(context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Cek Harian", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Pengingat cek harian");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
