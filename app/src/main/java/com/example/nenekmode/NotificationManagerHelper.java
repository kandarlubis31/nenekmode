package com.example.nenekmode;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/**
 * Helper notifikasi terpusat (channel proaktif dkk).
 */
public final class NotificationManagerHelper {

    private NotificationManagerHelper() {}

    public static void notifyProactive(Context context, String title, String text) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    "proactive_channel", "Sapaan Teman",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Sapaan proaktif dari asisten");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        android.app.Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new android.app.Notification.Builder(context, "proactive_channel")
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true);
        } else {
            builder = new android.app.Notification.Builder(context)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true);
        }

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(700, builder.build());
    }
}
