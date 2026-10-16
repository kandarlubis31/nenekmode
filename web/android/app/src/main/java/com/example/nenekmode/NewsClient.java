package com.example.nenekmode;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Klien RSS sederhana buat baca berita pagi.
 * Pakai HttpURLConnection (bukan OkHttp) supaya streaming baca baris-per-baris
 * gampang dan parsing pakai XmlPullParser bawaan Android.
 */
public class NewsClient {

    public interface NewsCallback {
        void onSuccess(List<String> headlines);
        void onError(String error);
    }

    public void getTopHeadlines(final NewsCallback callback) {
        // RSS feed detik.com - kategori news utama
        fetchFeed("https://rss.detik.com/index.php/detikcom_nasional", callback);
    }

    /** Versi sinkron untuk dipakai di background thread (mis. ProactiveReceiver). */
    public List<String> getTopHeadlinesSync() {
        try {
            java.net.URL url = new java.net.URL("https://rss.detik.com/index.php/detikcom_nasional");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) return new ArrayList<>();
            List<String> out = new ArrayList<>();
            try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                parser.setInput(in, null);
                int event = parser.getEventType();
                boolean insideItem = false;
                while (event != XmlPullParser.END_DOCUMENT) {
                    String tag = parser.getName();
                    if (event == XmlPullParser.START_TAG && "item".equals(tag)) {
                        insideItem = true;
                    } else if (event == XmlPullParser.END_TAG && "item".equals(tag)) {
                        insideItem = false;
                    } else if (event == XmlPullParser.START_TAG && "title".equals(tag) && insideItem) {
                        String text = parser.nextText();
                        if (text != null && !text.trim().isEmpty()) out.add(text.trim());
                    }
                    event = parser.next();
                }
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void fetchFeed(final String feedUrl, final NewsCallback callback) {
        Thread t = new Thread(() -> {
            try {
                URL url = new URL(feedUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                int code = conn.getResponseCode();
                if (code != 200) {
                    callback.onError("Server berita sedang sibuk.");
                    return;
                }

                List<String> headlines = new ArrayList<>();
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                    parser.setInput(in, null);
                    int event = parser.getEventType();
                    boolean insideItem = false;
                    while (event != XmlPullParser.END_DOCUMENT) {
                        String tag = parser.getName();
                        if (event == XmlPullParser.START_TAG && "item".equals(tag)) {
                            insideItem = true;
                        } else if (event == XmlPullParser.END_TAG && "item".equals(tag)) {
                            insideItem = false;
                        } else if (event == XmlPullParser.START_TAG && "title".equals(tag) && insideItem) {
                            String text = parser.nextText();
                            if (text != null && !text.trim().isEmpty()) headlines.add(text.trim());
                        }
                        event = parser.next();
                    }
                }

                if (headlines.isEmpty()) {
                    callback.onError("Daftar beritanya belum tersedia.");
                } else {
                    callback.onSuccess(headlines);
                }
            } catch (Exception e) {
                callback.onError("Gagal mengambil berita. Cek internetnya.");
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
