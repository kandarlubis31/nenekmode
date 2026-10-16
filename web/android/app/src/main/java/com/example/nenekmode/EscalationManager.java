package com.example.nenekmode;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.SmsManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Escalation check-in (jaring pengaman):
 * - Saat CheckInReceiver menyapa, escalation DIAKTIFKAN sementara dan alarm
 *   30 menit dipasang (requestCode 1400).
 * - Kalau lansia bicara (Activity/Service transkripsi) atau sudah menjawab
 *   cek harian sebelum alarm mati -> cancelEscalation() dipanggil, tidak terjadi apa-apa.
 * - Kalau alarm tetap mati (tidak ada respons 30 menit) -> SMS otomatis ke
 *   nomor darurat: "tolong hubungi/contact", lalu escalation dimatikan (sekali per cek).
 * Semua state di prefs (escalation_armed/escalation_ts) agar tahan reboot
 * (BootReceiver memasang ulang alarm-nya).
 */
public final class EscalationManager {

    public static final int REQUEST_CODE = 1400;
    private static final long DELAY_MS = 30 * 60 * 1000L; // 30 menit

    private EscalationManager() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
    }

    /** Dipanggil CheckInReceiver: mulai memantau, alarm 30 menit ke depan. */
    public static void arm(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean("escalation_enabled", true)) return;

        p.edit().putBoolean("escalation_armed", true)
                .putLong("escalation_ts", System.currentTimeMillis()).apply();

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, EscalationReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmScheduler.scheduleExact(ctx, System.currentTimeMillis() + DELAY_MS, pi);
    }

    /** Dipanggil begitu ada tanda hidup (lansia bicara / jawaban cek). */
    public static void cancelEscalation(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean("escalation_armed", false)) return;
        p.edit().putBoolean("escalation_armed", false).apply();

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, EscalationReceiver.class);
        am.cancel(PendingIntent.getBroadcast(ctx, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    /** Dipanggil EscalationReceiver: tidak ada respons -> kirim SMS ke keluarga. */
    public static void trigger(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean("escalation_armed", false)) return;
        p.edit().putBoolean("escalation_armed", false).apply(); // sekali per sesi cek

        String number = p.getString("emergency_number", "").trim();
        if (number.isEmpty() || number.contains("XXXXXXXXXX")) return;
        if (checkSelfPermission(ctx, android.Manifest.permission.SEND_SMS)) return;

        String elderName = p.getString("nenek_name", "Bu");
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String sms = "Laporan " + time + ": " + elderName
                + " tidak merespons cek harian (30 menit). Mohon segera hubungi beliau.";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SmsManager sm = ctx.getSystemService(SmsManager.class);
                if (sm != null) sm.sendTextMessage(number, null, sms, null, null);
            } else {
                SmsManager.getDefault().sendTextMessage(number, null, sms, null, null);
            }
            p.edit().putString("last_escalation", sms).apply();
        } catch (Exception ignored) {}
    }

    /** Pasang ulang alarm escalation setelah reboot bila masih armed. */
    public static void rearmIfArmed(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean("escalation_armed", false)) {
            long ts = p.getLong("escalation_ts", 0);
            long remaining = ts + DELAY_MS - System.currentTimeMillis();
            if (remaining <= 0) {
                // sudah lewat tapi belum trigger (HP mati) -> langsung eskalasi
                trigger(ctx);
            } else {
                AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                if (am == null) return;
                Intent i = new Intent(ctx, EscalationReceiver.class);
                PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                AlarmScheduler.scheduleExact(ctx, System.currentTimeMillis() + remaining, pi);
            }
        }
    }

    private static boolean checkSelfPermission(Context ctx, String permission) {
        return ctx.checkSelfPermission(permission)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
