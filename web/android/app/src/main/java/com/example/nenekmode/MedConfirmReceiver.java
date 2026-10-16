package com.example.nenekmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Follow-up 15 menit setelah pengingat obat:
 * - kalau belum dijawab -> ingatkan lagi dengan lembut
 * - selalu jadwalkan ulang slot obat untuk hari berikutnya
 */
public class MedConfirmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        int index = intent.getIntExtra("index", 0);

        if (prefs.getBoolean("med_confirm_waiting", false)) {
            String elderName = prefs.getString("nenek_name", "Bu");
            SpeechUtil.speak(context, elderName + ", kalau sudah minum obat bilang 'sudah' ya, "
                    + "biar hati " + prefs.getString("assistant_name", "Kandar") + " tenang.");
        }

        // jadwalkan ulang slot ini untuk besok
        try {
            JSONObject t = ReminderReceiver.getMedTimes(prefs).getJSONObject(index);
            if (t.optBoolean("en", true)) {
                ReminderReceiver.scheduleSlot(context, index, t.getInt("h"), t.getInt("m"));
            }
        } catch (Exception ignored) {}
    }
}
