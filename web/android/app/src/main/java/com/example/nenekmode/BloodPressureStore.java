package com.example.nenekmode;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Riwayat tekanan darah (opsional): user bilang "tekanan darahku 140/90",
 * tersimpan maksimal 30 entry terakhir. Bisa ditanya balik + peringatan bila tinggi.
 * Kategori mengikuti acuan umum (bukan diagnosis):
 *  - >= 180 dan/atau >= 120 : sangat tinggi -> sarankan ke dokter
 *  - >= 140 dan/atau >= 90  : tinggi
 *  - >= 130 dan/atau >= 85  : agak tinggi
 *  - di bawah itu           : normal
 */
public class BloodPressureStore {

    private static final String KEY = "bp_history";
    private static final int MAX_ENTRIES = 30;

    public static class Entry {
        public String date;   // "17 Sep 14:30"
        public int sys;
        public int dia;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
    }

    /** @return true jika berhasil disimpan, false jika format tidak valid. */
    public static boolean add(Context ctx, int sys, int dia) {
        if (sys < 50 || sys > 260 || dia < 30 || dia > 180 || dia >= sys) return false;
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            JSONObject e = new JSONObject();
            e.put("date", new SimpleDateFormat("dd MMM HH:mm", new Locale("id", "ID")).format(new Date()));
            e.put("sys", sys);
            e.put("dia", dia);
            JSONArray out = new JSONArray();
            out.put(e);
            for (int i = 0; i < arr.length() && out.length() < MAX_ENTRIES; i++) {
                out.put(arr.get(i));
            }
            prefs(ctx).edit().putString(KEY, out.toString()).apply();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public static List<Entry> getAll(Context ctx) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.date = o.optString("date", "");
                e.sys = o.optInt("sys");
                e.dia = o.optInt("dia");
                if (e.sys > 0) out.add(e);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static Entry latest(Context ctx) {
        List<Entry> all = getAll(ctx);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Jumlah entry tersimpan. */
    public static int count(Context ctx) {
        return getAll(ctx).size();
    }

    /**
     * Kalimat riwayat singkat buat dibacakan: "Sebelumnya 128 atas 82, dan 130 atas 85."
     * (3 entry terakhir setelah yang terbaru; dipakai di pengumuman tombol kesehatan.)
     */
    public static String speechHistory(Context ctx, String elderName) {
        List<Entry> all = getAll(ctx);
        if (all.size() <= 1) return "";
        StringBuilder sb = new StringBuilder("Riwayat sebelumnya: ");
        int n = Math.min(3, all.size() - 1);
        for (int i = 1; i <= n; i++) {
            Entry e = all.get(i);
            sb.append(e.sys).append(" atas ").append(e.dia);
            if (!e.date.isEmpty()) sb.append(" (").append(e.date).append(")");
            if (i < n) sb.append(", ");
        }
        sb.append(".");
        return sb.toString();
    }

    /** Kalimat respon suara setelah nyatat. */
    public static String speechAfterAdd(Context ctx, String elderName, int sys, int dia) {
        String base = "Oke, tekanan darah " + sys + " atas " + dia + " bawah sudah dicatat ya " + elderName + ".";
        if (sys >= 180 || dia >= 120) {
            return base + " Wah, ini tinggi sekali. Sebaiknya hubungi dokter sekarang ya.";
        }
        if (sys >= 140 || dia >= 90) {
            return base + " Ini termasuk tinggi. Kurangi asin-asinan, istirahat yang cukup, dan kalau sering gitu kabari keluarga atau dokter ya.";
        }
        if (sys >= 130 || dia >= 85) {
            return base + " Agak tinggi sedikit. Jaga makan dan istirahat ya.";
        }
        return base + " Alhamdulillah, tekanannya bagus. Pertahankan ya.";
    }

    /** Kalimat jawaban "berapa tekanan darahku?". */
    public static String speechLast(Context ctx, String elderName) {
        Entry e = latest(ctx);
        if (e == null) {
            return "Belum ada catatan tekanan darah " + elderName + ". Kalau sudah diukur, bilang aja, misalnya tekanan darahku 130 atas 85.";
        }
        return "Catatan terakhir, tanggal " + e.date + ", tekanan darah " + e.sys
                + " atas " + e.dia + "." + (e.sys >= 140 || e.dia >= 90
                ? " Itu agak tinggi ya, jangan lupa kontrol ke dokter." : " Alhamdulillah masih bagus.");
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }
}
