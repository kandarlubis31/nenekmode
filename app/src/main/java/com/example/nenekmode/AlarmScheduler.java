package com.example.nenekmode;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;

/**
 * Helper pemakaian exact alarm yang aman:
 * - Android 12+ (API 31) butuh izin SCHEDULE_EXACT_ALARM. Kalau belum dikasih,
 *   fallback otomatis ke setAndAllowWhileIdle (inexact) supaya tidak crash.
 */
public final class AlarmScheduler {

    private AlarmScheduler() {}

    public static void scheduleExact(Context context, long triggerAtMillis, PendingIntent operation) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
                return;
            }
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
        }
    }
}
