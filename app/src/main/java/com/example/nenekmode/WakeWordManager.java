package com.example.nenekmode;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Wake word offline fase 2 (Vosk).
 * Model Indonesia tidak tersedia resmi dari alphacephei, jadi model diambil
 * dari URL yang diisi user (mis. model komunitas / kaldi-id hasil build sendiri).
 * Kalau model belum ada -> fallback otomatis ke gate nama asisten (fase 1).
 */
public class WakeWordManager {

    private static final String TAG = "WakeWord";
    private static final String DIR_NAME = "vosk_model";

    private final Context context;
    private Model model;
    private boolean loading = false;

    public interface ReadyCallback {
        void onReady(boolean ok, String message);
    }

    public WakeWordManager(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
    }

    public static String getModelUrl(Context context) {
        return context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE)
                .getString("vosk_model_url", "");
    }

    public boolean isModelDownloaded() {
        return new File(context.getFilesDir(), DIR_NAME).exists();
    }

    /**
     * Siapkan model. Kalau sudah terunduh, load langsung.
     * Kalau belum, download dari URL di settings lalu unzip.
     */
    public void ensureReady(ReadyCallback cb) {
        if (model != null) {
            cb.onReady(true, "ok");
            return;
        }
        if (loading) {
            cb.onReady(false, "loading");
            return;
        }
        if (!isModelDownloaded()) {
            String url = getModelUrl(context);
            if (url.trim().isEmpty()) {
                cb.onReady(false, "no_url");
                return;
            }
            downloadAndExtract(url.trim(), cb);
        } else {
            loadModel(cb);
        }
    }

    private void downloadAndExtract(String url, ReadyCallback cb) {
        loading = true;
        Thread t = new Thread(() -> {
            File dest = new File(context.getFilesDir(), DIR_NAME);
            File tmpZip = new File(context.getCacheDir(), "vosk_model.zip");
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    loading = false;
                    cb.onReady(false, "download_failed_" + code);
                    return;
                }

                long total = conn.getContentLength();
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream out = new FileOutputStream(tmpZip)) {
                    byte[] buf = new byte[8192];
                    long read = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        read += n;
                        if (total > 0 && read % (5 * 1024 * 1024) < buf.length) {
                            Log.i(TAG, "Download model: " + (read / (1024 * 1024)) + "MB");
                        }
                    }
                }

                // unzip: ambil folder pertama di dalam zip sebagai isi DIR_NAME
                File extractRoot = new File(context.getCacheDir(), "vosk_extract");
                deleteRecursive(extractRoot);
                extractRoot.mkdirs();
                try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(
                        new java.io.FileInputStream(tmpZip)))) {
                    ZipEntry ze;
                    while ((ze = zin.getNextEntry()) != null) {
                        File f = new File(extractRoot, ze.getName());
                        if (ze.isDirectory()) {
                            f.mkdirs();
                            continue;
                        }
                        File parent = f.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (FileOutputStream fo = new FileOutputStream(f)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
                        }
                    }
                }

                // cari folder yang berisi am/final.mdl
                File modelDir = findModelDir(extractRoot);
                if (modelDir == null) {
                    loading = false;
                    cb.onReady(false, "invalid_model");
                    return;
                }
                deleteRecursive(dest);
                if (!modelDir.renameTo(dest)) {
                    // rename lintas-volume bisa gagal; fallback copy
                    copyRecursive(modelDir, dest);
                    deleteRecursive(extractRoot);
                }
                tmpZip.delete();

                loading = false;
                loadModel(cb);
            } catch (Exception e) {
                Log.e(TAG, "Download model gagal: " + e.getMessage());
                loading = false;
                cb.onReady(false, "error");
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void loadModel(ReadyCallback cb) {
        loading = true;
        Thread t = new Thread(() -> {
            try {
                File dir = new File(context.getFilesDir(), DIR_NAME);
                model = new Model(dir.getAbsolutePath());
                loading = false;
                cb.onReady(true, "ok");
            } catch (Exception e) {
                Log.e(TAG, "Load model gagal: " + e.getMessage());
                model = null;
                loading = false;
                cb.onReady(false, "load_failed");
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** Sinkron: buat recognizer dari model yang sudah dimuat (atau null). */
    public Recognizer createRecognizer(float sampleRate) {
        try {
            if (model == null) return null;
            return new Recognizer(model, sampleRate);
        } catch (Exception e) {
            Log.e(TAG, "Recognizer gagal: " + e.getMessage());
            return null;
        }
    }

    public boolean isLoaded() {
        return model != null;
    }

    private static File findModelDir(File root) {
        File am = new File(root, "am/final.mdl");
        if (am.exists()) return root;
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) {
                    File found = findModelDir(k);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyRecursive(k, new File(dst, k.getName()));
        } else {
            try (InputStream in = new java.io.FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }
}
