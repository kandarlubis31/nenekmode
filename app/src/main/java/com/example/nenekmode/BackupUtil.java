package com.example.nenekmode;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Backup & restore seluruh pengaturan + memori jangka panjang ke satu file JSON.
 * Dipakai saat ganti HP biar keluarga tidak setup ulang dari nol.
 * Catatan keamanan: API key (BYOK) sengaja TIDAK ikut di-backup.
 * Alur: saveSettings() -> BackupUtil.backup() -> SAF create -> onActivityResult(2001)
 *       restore: SAF open -> onActivityResult(2002) -> applyBackup() -> dialog restart app.
 */
public final class BackupUtil {

    public static final int REQUEST_BACKUP = 2001;
    public static final int REQUEST_RESTORE = 2002;

    private BackupUtil() {}

    private static final String PREFS_NAME = "Nenek_Assistant";
    /** Key yang tidak ikut dibackup (runtime state & kredensial). */
    private static final String[] EXCLUDE = {
            "byok_api_key", "byok_cooldown", "chat_history",
            "med_confirm_waiting", "quiz_waiting", "water_confirm_waiting",
            "pending_followup", "last_spoken"
    };

    private static boolean excluded(String key) {
        for (String e : EXCLUDE) {
            if (e.equals(key)) return true;
        }
        return false;
    }

    public static void backup(Activity activity) {
        try {
            JSONObject root = new JSONObject();
            root.put("app", "NenekMode");
            root.put("version", 1);

            SharedPreferences p = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
            JSONObject data = new JSONObject();
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (excluded(e.getKey())) continue;
                Object v = e.getValue();
                if (v instanceof Boolean) data.put(e.getKey(), (Boolean) v);
                else if (v instanceof Integer) data.put(e.getKey(), (Integer) v);
                else if (v instanceof Float) data.put(e.getKey(), (Double) ((Float) v).doubleValue());
                else if (v instanceof Long) data.put(e.getKey(), (Long) v);
                else if (v instanceof String) data.put(e.getKey(), (String) v);
            }
            root.put("data", data);

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "nenekmode_backup.json");
            activity.startActivityForResult(intent, REQUEST_BACKUP);
        } catch (Exception ex) {
            Toast.makeText(activity, "Backup gagal: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Panggil dari onActivityResult(requestCode == REQUEST_BACKUP). */
    public static void onBackupResult(Activity activity, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        try {
            SharedPreferences p = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
            JSONObject all = new JSONObject();
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (excluded(e.getKey())) continue;
                Object v = e.getValue();
                if (v instanceof Boolean) all.put(e.getKey(), (Boolean) v);
                else if (v instanceof Integer) all.put(e.getKey(), (Integer) v);
                else if (v instanceof Float) all.put(e.getKey(), (Double) ((Float) v).doubleValue());
                else if (v instanceof Long) all.put(e.getKey(), (Long) v);
                else if (v instanceof String) all.put(e.getKey(), (String) v);
            }
            JSONObject root = new JSONObject();
            root.put("app", "NenekMode");
            root.put("version", 1);
            root.put("data", all);

            try (OutputStream os = activity.getContentResolver()
                    .openOutputStream(data.getData())) {
                if (os != null) {
                    os.write(root.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
            Toast.makeText(activity, "Backup tersimpan ya!", Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(activity, "Backup gagal: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static void restore(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            activity.startActivityForResult(intent, REQUEST_RESTORE);
        } catch (Exception ex) {
            Toast.makeText(activity, "Restore gagal: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Panggil dari onActivityResult(requestCode == REQUEST_RESTORE). */
    public static void onRestoreResult(Activity activity, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    activity.getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            if (!"NenekMode".equals(root.optString("app"))) {
                Toast.makeText(activity, "File bukan backup NenekMode", Toast.LENGTH_LONG).show();
                return;
            }
            JSONObject all = root.optJSONObject("data");
            if (all == null) {
                Toast.makeText(activity, "File backup tidak valid", Toast.LENGTH_LONG).show();
                return;
            }

            // simpan dulu ke staging, baru apply (biar gak setengah jalan)
            SharedPreferences p = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
            SharedPreferences.Editor editor = p.edit();
            java.util.Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = all.get(k);
                if (excluded(k)) continue;
                if (v instanceof Boolean) editor.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) editor.putInt(k, (Integer) v);
                else if (v instanceof Double) editor.putFloat(k, (float) (double) (Double) v);
                else if (v instanceof Long) editor.putLong(k, (Long) v);
                else if (v instanceof String) editor.putString(k, (String) v);
            }
            editor.apply();

            Toast.makeText(activity, "Pengaturan dipulihkan. App akan dibuka ulang ya.", Toast.LENGTH_LONG).show();
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        Intent i = activity.getPackageManager()
                                .getLaunchIntentForPackage(activity.getPackageName());
                        if (i != null) {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            activity.startActivity(i);
                        }
                        activity.finishAffinity();
                    }, 1500);
        } catch (Exception ex) {
            Toast.makeText(activity, "Restore gagal: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
