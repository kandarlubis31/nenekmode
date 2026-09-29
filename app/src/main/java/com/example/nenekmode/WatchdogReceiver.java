package com.example.nenekmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;

/**
 * Watchdog Mode Teman: dipanggil alarm berkala (requestCode 1200, ~15 menit).
 * - keep_alive aktif tapi service mati (dimatikan sistem / OEM killer) -> start ulang.
 * - Minta pengecualian battery optimization sekali (Android 6+) biar service
 *   tidak dibunuh agresif (Xiaomi/Oppo/Vivo dkk).
 * - Sekalian jaring pengaman: pastikan alarm obat, adzan, proaktif tetap terjadwal.
 * Catatan: pengecualian battery optimization TIDAK menjamin 100% di semua OEM;
 * keluarga tetap disarankan mengaktifkan "autostart" + mengunci app di recents.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    public static final long INTERVAL_MS = 15 * 60 * 1000L;
    public static final int REQUEST_CODE = 1200;

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);

        if (prefs.getBoolean("keep_alive", false) && !CompanionService.isRunning) {
            CompanionService.start(context); // startAsForeground -> notif muncul lagi
        }

        ensureBatteryIgnoreRequested(context, prefs);

        // jaring pengaman: alarm besar bisa dibatalkan sistem saat device idle
        if (prefs.getBoolean("reminder_enabled", false)) {
            ReminderReceiver.setReminders(context);
        }
        ProactiveReceiver.rescheduleNext(context);
        if (PrayerTimes.isEnabled(context)) {
            PrayerTimes.scheduleDay(context);
        }

        WatchdogReceiver.scheduleNext(context);
    }

    /** Pasang alarm watchdog berikutnya (inexact cukup — bukan fitur presisi waktu). */
    public static void scheduleNext(Context context) {
        android.app.AlarmManager am =
                (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, WatchdogReceiver.class);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(context,
                REQUEST_CODE, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = System.currentTimeMillis() + INTERVAL_MS;
        if (android.os.Build.VERSION.SDK_INT >= 31
                && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt, pi);
        } else {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    public static void cancel(Context context) {
        android.app.AlarmManager am =
                (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, WatchdogReceiver.class);
        am.cancel(android.app.PendingIntent.getBroadcast(context, REQUEST_CODE, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
    }

    private static void ensureBatteryIgnoreRequested(Context context, SharedPreferences prefs) {
        if (prefs.getBoolean("battery_ignore_requested", false)) return;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            String pkg = context.getPackageName();
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                @SuppressWarnings("BatteryLife")
                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            }
            prefs.edit().putBoolean("battery_ignore_requested", true).apply();
        } catch (Exception ignored) {
            // beberapa ROM memblokir intent ini — abaikan
        }
    }
}
