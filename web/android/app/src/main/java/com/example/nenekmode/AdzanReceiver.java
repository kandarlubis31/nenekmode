package com.example.nenekmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Pengingat adzan 5 waktu + refresh harian.
 * - kind="prayer" : umumkan waktu sholat lewat suara + notifikasi
 * - kind="refresh": ambil jadwal hari ini dari Aladhan, pasang ulang alarm
 */
public class AdzanReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        if (!PrayerTimes.isEnabled(context)) return;

        String kind = intent != null ? intent.getStringExtra("kind") : "prayer";

        if ("refresh".equals(kind)) {
            // refresh jadwal harian (00:10) — dari bg thread, lalu pasang alarm hari ini
            new Thread(() -> PrayerTimes.refreshIfNeededAsync(context, null)).start();
            return;
        }

        int index = intent != null ? intent.getIntExtra("index", 0) : 0;
        if (index < 0 || index >= PrayerTimes.PRAYERS.length) return;
        String prayerName = PrayerTimes.PRAYERS[index][0];

        String elderName = prefs.getString("nenek_name", "Bu");
        String line = "Assalamualaikum " + elderName + ", sudah masuk waktu sholat "
                + prayerName + " untuk wilayah sekitar. Yuk kita sholat dulu ya.";

        SpeechUtil.speak(context, line);
        NotificationManagerHelper.notifyProactive(context, "Waktu Sholat " + prayerName, line);
    }
}
