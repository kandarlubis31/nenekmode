package com.example.nenekmode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Alarm 30 menit setelah cek harian: kalau tidak ada respons -> eskalasi ke keluarga.
 */
public class EscalationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        EscalationManager.trigger(context);
    }
}
