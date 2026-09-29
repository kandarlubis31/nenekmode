package com.example.nenekmode;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Util jadwal adzan: cache harian, penjadwalan alarm 5 waktu,
 * dan helper teks buat dibacakan (jadwal lengkap / waktu berikutnya).
 * requestCode alarm: 1100+index (adzan), 1150 (refresh harian 00:10).
 */
public final class PrayerTimes {

    /** [0] = nama Indonesia buat suara, [1] = key di API Aladhan. */
    public static final String[][] PRAYERS = {
        {"Subuh", "Fajr"},
        {"Dzuhur", "Dhuhr"},
        {"Ashar", "Asr"},
        {"Maghrib", "Maghrib"},
        {"Isya", "Isha"},
    };

    private static final String KEY = "prayer_times_cache";

    private PrayerTimes() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean("adzan_enabled", true);
    }

    // ================= CACHE =================

    public static JSONObject getCache(Context ctx) {
        try {
            String raw = prefs(ctx).getString(KEY, "");
            if (raw.isEmpty()) return null;
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveCache(Context ctx, String date, JSONObject times) {
        try {
            JSONObject wrap = new JSONObject();
            wrap.put("date", date);
            wrap.put("times", times);
            prefs(ctx).edit().putString(KEY, wrap.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static boolean isStale(Context ctx) {
        JSONObject c = getCache(ctx);
        if (c == null) return true;
        String today = new java.text.SimpleDateFormat("dd-MM-yyyy", Locale.US).format(new java.util.Date());
        return !today.equals(c.optString("date"));
    }

    /** Waktu satu sholat (nama Indonesia), format "HH:mm" atau "" jika kosong. */
    public static String timeOf(Context ctx, String displayName) {
        JSONObject c = getCache(ctx);
        if (c == null) return "";
        try {
            for (String[] p : PRAYERS) {
                if (p[0].equalsIgnoreCase(displayName)) {
                    return c.getJSONObject("times").optString(p[1], "").trim();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static int toMinutes(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim().replaceAll("[^0-9].*$", ""));
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    /** "04:23" -> "jam 4 lewat 23" (enak dibaca TTS Indonesia). */
    public static String fmt(String hhmm) {
        int mins = toMinutes(hhmm);
        if (mins < 0) return "pukul " + hhmm;
        int h = mins / 60, m = mins % 60;
        return m == 0 ? ("jam " + h) : ("jam " + h + " lewat " + m);
    }

    // ================= PENJADWALAN =================

    /** Pasang alarm untuk semua waktu sholat hari ini yang belum lewat + refresh besok. */
    public static void scheduleDay(Context ctx) {
        if (!isEnabled(ctx)) return;
        JSONObject c = getCache(ctx);
        if (c == null) return;

        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        try {
            JSONObject times = c.getJSONObject("times");
            for (int i = 0; i < PRAYERS.length; i++) {
                int tMin = toMinutes(times.optString(PRAYERS[i][1], "").trim());
                if (tMin < 0 || tMin <= nowMin) continue;
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, tMin / 60);
                cal.set(Calendar.MINUTE, tMin % 60);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                AlarmScheduler.scheduleExact(ctx, cal.getTimeInMillis(), prayerPi(ctx, i));
            }
        } catch (Exception ignored) {}

        scheduleRefresh(ctx);
    }

    /** Refresh harian jam 00:10 — waktu sholat bergeser tiap hari. */
    public static void scheduleRefresh(Context ctx) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 10);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        AlarmScheduler.scheduleExact(ctx, cal.getTimeInMillis(), refreshPi(ctx));
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < PRAYERS.length; i++) {
            am.cancel(prayerPi(ctx, i));
        }
        am.cancel(refreshPi(ctx));
    }

    private static PendingIntent prayerPi(Context ctx, int index) {
        Intent i = new Intent(ctx, AdzanReceiver.class);
        i.putExtra("kind", "prayer");
        i.putExtra("index", index);
        return PendingIntent.getBroadcast(ctx, 1100 + index, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent refreshPi(Context ctx) {
        Intent i = new Intent(ctx, AdzanReceiver.class);
        i.putExtra("kind", "refresh");
        return PendingIntent.getBroadcast(ctx, 1150, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Ambil jadwal baru kalau stale (pakai koordinat terakhir / default Jakarta),
     * lalu pasang alarm hari ini. onDone boleh null, dipanggil dari bg thread.
     */
    public static void refreshIfNeededAsync(Context ctx, final Runnable onDone) {
        if (!isEnabled(ctx)) return;
        if (!isStale(ctx)) {
            scheduleDay(ctx);
            if (onDone != null) onDone.run();
            return;
        }
        new Thread(() -> {
            SharedPreferences p = prefs(ctx);
            double lat = p.getFloat("last_lat", -6.2f);
            double lon = p.getFloat("last_lon", 106.8f);
            JSONObject fresh = PrayerTimeClient.fetchSync(lat, lon);
            if (fresh != null) {
                try {
                    saveCache(ctx, fresh.getString("date"), fresh.getJSONObject("times"));
                } catch (Exception ignored) {}
            }
            scheduleDay(ctx);
            if (onDone != null) onDone.run();
        }).start();
    }

    // ================= TEKS SUARA =================

    /** Jadwal lengkap hari ini buat dibacakan. */
    public static String scheduleSpeech(Context ctx, String elderName) {
        StringBuilder sb = new StringBuilder("Jadwal adzan hari ini: ");
        boolean any = false;
        for (String[] p : PRAYERS) {
            String t = timeOf(ctx, p[0]);
            if (!t.isEmpty()) {
                if (any) sb.append(", ");
                sb.append(p[0]).append(" ").append(fmt(t));
                any = true;
            }
        }
        if (!any) {
            return "Belum bisa ambil jadwal adzan. Coba lagi nanti ya " + elderName + ".";
        }
        sb.append(". Jangan lupa sholat ya ").append(elderName).append(".");
        return sb.toString();
    }

    /** Satu baris pendek buat Mode papan info: "Sholat berikutnya: Maghrib 17:45". */
    public static String nextPrayerShort(Context ctx) {
        JSONObject c = getCache(ctx);
        if (c == null) return "Sholat berikutnya: (jadwal belum tersedia)";
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        try {
            JSONObject times = c.getJSONObject("times");
            for (String[] p : PRAYERS) {
                int tMin = toMinutes(times.optString(p[1], "").trim());
                if (tMin < 0) continue;
                if (tMin > nowMin) {
                    return "Sholat berikutnya: " + p[0] + " " + times.optString(p[1], "").trim();
                }
            }
        } catch (Exception ignored) {}
        return "Semua waktu sholat hari ini sudah lewat";
    }

    /** Info waktu sholat terdekat / yang sedang masuk. */
    public static String nextPrayerSpeech(Context ctx, String elderName) {
        JSONObject c = getCache(ctx);
        if (c == null) return "Belum ada data jadwal adzan, coba lagi nanti ya " + elderName + ".";

        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        try {
            JSONObject times = c.getJSONObject("times");
            for (String[] p : PRAYERS) {
                int tMin = toMinutes(times.optString(p[1], "").trim());
                if (tMin < 0) continue;
                if (nowMin >= tMin && nowMin < tMin + 30) {
                    return "Sudah masuk waktu sholat " + p[0] + " ya " + elderName + ". Yuk sholat dulu.";
                }
                if (tMin > nowMin) {
                    int diff = tMin - nowMin;
                    String when;
                    if (diff >= 60) {
                        int h = diff / 60, m = diff % 60;
                        when = m > 0 ? (h + " jam lebih " + m + " menit") : (h + " jam");
                    } else {
                        when = diff + " menit";
                    }
                    return "Adzan " + p[0] + " sekitar " + when + " lagi ya " + elderName + ".";
                }
            }
        } catch (Exception ignored) {}
        return "Semua waktu sholat hari ini sudah lewat. Besok ada lagi ya " + elderName + ".";
    }
}
