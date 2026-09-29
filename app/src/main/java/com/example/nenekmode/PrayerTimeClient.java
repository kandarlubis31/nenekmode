package com.example.nenekmode;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * Klien API Aladhan (aladhan.com) untuk jadwal sholat.
 * Gratis, tanpa API key. Method 20 = KEMENAG (Kementerian Agama RI).
 */
public class PrayerTimeClient {

    private static final String BASE = "https://api.aladhan.com/v1/timings/";

    public interface PrayerCallback {
        void onSuccess(String date, JSONObject timings);
        void onError(String error);
    }

    /**
     * Ambil jadwal hari ini (sinkron, panggil dari background thread).
     * @return {"date":"17-09-2026","times":{"Fajr":"04:23",...}} atau null jika gagal
     */
    public static JSONObject fetchSync(double lat, double lon) {
        try {
            String datePath = new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(new Date());
            String url = BASE + datePath
                    + "?latitude=" + lat
                    + "&longitude=" + lon
                    + "&method=20"; // KEMENAG Indonesia

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int code = conn.getResponseCode();
            if (code != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }

            JSONObject root = new JSONObject(sb.toString());
            JSONObject data = root.getJSONObject("data");
            JSONObject rawTimings = data.getJSONObject("timings");
            String date = data.getJSONObject("date")
                    .getJSONObject("gregorian").getString("date");

            // bersihkan nilai "04:23 (WIB)" -> "04:23"
            JSONObject clean = new JSONObject();
            Iterator<String> keys = rawTimings.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = rawTimings.optString(k, "").trim();
                int sp = v.indexOf(' ');
                if (sp > 0) v = v.substring(0, sp);
                clean.put(k, v);
            }

            JSONObject out = new JSONObject();
            out.put("date", date);
            out.put("times", clean);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Versi async. */
    public void getTimings(double lat, double lon, final PrayerCallback callback) {
        new Thread(() -> {
            JSONObject res = fetchSync(lat, lon);
            if (res == null) {
                callback.onError("Gagal mengambil jadwal sholat.");
            } else {
                try {
                    callback.onSuccess(res.getString("date"), res.getJSONObject("times"));
                } catch (Exception e) {
                    callback.onError("Format jadwal sholat tidak sesuai.");
                }
            }
        }).start();
    }
}
