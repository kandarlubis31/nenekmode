package com.example.nenekmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Re-schedule semua alarm setelah HP reboot/restart.
 * Tanpa receiver ini, jadwal minum obat & cek harian hilang tiap HP dimatikan.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !QUICKBOOT_POWERON.equals(action)) return;

        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);

        if (prefs.getBoolean("reminder_enabled", false)) {
            ReminderReceiver.setReminders(context);
        }
        if (prefs.getBoolean("checkin_enabled", false)) {
            CheckInReceiver.setCheckIn(context,
                    prefs.getInt("checkin_hour", 9),
                    prefs.getInt("checkin_minute", 0));
        }

        // jadwal adzan: refresh data + pasang alarm hari ini
        if (PrayerTimes.isEnabled(context)) {
            PrayerTimes.refreshIfNeededAsync(context, null);
        }

        // fitur opsional
        WaterReceiver.scheduleNext(context);
        BirthdayReceiver.scheduleNext(context);

        // watchdog Mode Teman + nyala ulang service & papan info setelah reboot
        WatchdogReceiver.scheduleNext(context);

        // escalation cek harian yang masih armed -> pasang ulang alarm sisa waktunya
        EscalationManager.rearmIfArmed(context);
        if (prefs.getBoolean("keep_alive", false)) {
            CompanionService.start(context);
        }
        // papan info di versi web = layar JS (App.tsx routing otomatis dari prefs
        // board_mode) — tidak ada BoardActivity native, jadi cukup buka MainActivity
        if (prefs.getBoolean("board_mode", false)) {
            Intent board = new Intent(context, com.example.nenekmode.web.MainActivity.class);
            board.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(board);
        }
    }
}
