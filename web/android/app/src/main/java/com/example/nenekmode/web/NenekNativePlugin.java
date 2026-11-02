package com.example.nenekmode.web;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import com.example.nenekmode.AdzanReceiver;
import com.example.nenekmode.AlarmScheduler;
import com.example.nenekmode.BirthdayReceiver;
import com.example.nenekmode.CompanionService;
import com.example.nenekmode.EscalationReceiver;
import com.example.nenekmode.MedConfirmReceiver;
import com.example.nenekmode.ProactiveReceiver;
import com.example.nenekmode.ReminderReceiver;
import com.example.nenekmode.WaterReceiver;
import com.example.nenekmode.WatchdogReceiver;
import com.example.nenekmode.CheckInReceiver;

import java.util.Map;

/**
 * Jembatan JS (WebView) -> mesin native asli (kode Java app lama, di-port utuh).
 * TTS, alarm exact, SMS, telpon, SIM, Mode Teman (CompanionService), battery,
 * lokasi, dan sinkronisasi SharedPreferences "Nenek_Assistant".
 */
@CapacitorPlugin(
        name = "NenekNative",
        permissions = {
                @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "mic")
        }
)
public class NenekNativePlugin extends Plugin {

    private static final String PREFS_NAME = "Nenek_Assistant";

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsInitFailed = false;

    // ================= TTS =================

    private void ensureTts() {
        if (tts != null || ttsInitFailed) return;
        tts = new TextToSpeech(getContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                ttsInitFailed = true;
                return;
            }
            int langResult = tts.setLanguage(new java.util.Locale("id", "ID"));
            if (langResult < 0) {
                tts.setLanguage(java.util.Locale.getDefault());
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) { }

                @Override
                public void onDone(String utteranceId) {
                    PluginCall call = pendingCalls.remove(utteranceId);
                    if (call != null) call.resolve();
                }

                @Override
                public void onError(String utteranceId) {
                    PluginCall call = pendingCalls.remove(utteranceId);
                    if (call != null) call.resolve(); // resolve juga biar JS gak nyangkut
                }
            });
            ttsReady = true;
        });
    }

    private final Map<String, PluginCall> pendingCalls =
            new java.util.concurrent.ConcurrentHashMap<>();

    @PluginMethod
    public void speak(PluginCall call) {
        ensureTts();
        String text = call.getString("text", "");
        float pitch = call.getDouble("pitch", 0.85).floatValue();
        float rate = call.getDouble("rate", 0.75).floatValue();
        double volume = call.getDouble("volume", 1.0);

        if (tts == null || text.isEmpty()) {
            call.resolve();
            return;
        }

        if (ttsInitFailed) {
            call.resolve();
            return;
        }
        // tunggu TTS ready (init bisa async, di HP lambat > 0.5 dtk). Dulu
        // hanya dicek sekali setelah 500 ms lalu call di-resolve tanpa
        // bersuara -> sapaan pembuka (ucapan pertama) hilang diam-diam.
        if (!ttsReady) {
            waitForTts(new android.os.Handler(android.os.Looper.getMainLooper()),
                    call, text, pitch, rate, volume, 0);
            return;
        }
        speakNow(call, text, pitch, rate, volume);
    }

    /** Tahan ucapan sampai TextToSpeech siap (maks ~6 dtk), jangan dibuang. */
    private void waitForTts(android.os.Handler handler, PluginCall call, String text,
                            float pitch, float rate, double volume, int attempt) {
        if (ttsReady) {
            speakNow(call, text, pitch, rate, volume);
            return;
        }
        if (ttsInitFailed || attempt >= 12) {
            call.resolve();
            return;
        }
        handler.postDelayed(
                () -> waitForTts(handler, call, text, pitch, rate, volume, attempt + 1),
                500);
    }

    private void speakNow(PluginCall call, String text, float pitch, float rate, double volume) {
        try {
            tts.setPitch(pitch);
            tts.setSpeechRate(rate);
            String id = "TTS_" + System.currentTimeMillis();
            pendingCalls.put(id, call);
            android.os.Bundle params = new android.os.Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (float) volume);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);
        } catch (Exception e) {
            call.resolve();
        }
    }

    @PluginMethod
    public void stopSpeaking(PluginCall call) {
        try {
            if (tts != null) tts.stop();
        } catch (Exception ignored) { }
        // Utterance yang di-flush/di-stop tidak selalu memanggil onDone ->
        // resolve semua call yang masih menunggu biar JS tidak nyangkut
        // (antrian TTS di sisi JS bisa berhenti permanen kalau tidak).
        for (String id : new java.util.ArrayList<>(pendingCalls.keySet())) {
            PluginCall pending = pendingCalls.remove(id);
            if (pending != null) pending.resolve();
        }
        call.resolve();
    }

    @PluginMethod
    public void isSpeaking(PluginCall call) {
        boolean speaking = tts != null && tts.isSpeaking();
        if (!speaking) {
            // TTS receiver native (SpeechUtil) punya instance terpisah —
            // cek juga penanda waktu yang mereka tulis di prefs
            long until = getContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getLong("tts_busy_until", 0L);
            speaking = System.currentTimeMillis() < until;
        }
        JSObject r = new JSObject();
        r.put("speaking", speaking);
        call.resolve(r);
    }

    // ================= ALARM =================

    private PendingIntent alarmPi(String kind, int index, int code) {
        Context ctx = getContext();
        Intent i;
        switch (kind) {
            case "reminder":
            case "reminder_snooze":
                i = new Intent(ctx, ReminderReceiver.class);
                i.putExtra("index", index);
                break;
            case "reminder_confirm":
                i = new Intent(ctx, MedConfirmReceiver.class);
                i.putExtra("index", index);
                break;
            case "checkin":
                i = new Intent(ctx, CheckInReceiver.class);
                break;
            case "escalation":
                i = new Intent(ctx, EscalationReceiver.class);
                break;
            case "prayer":
                i = new Intent(ctx, AdzanReceiver.class);
                i.putExtra("kind", "prayer");
                i.putExtra("index", index);
                break;
            case "refresh":
                i = new Intent(ctx, AdzanReceiver.class);
                i.putExtra("kind", "refresh");
                break;
            case "water":
                i = new Intent(ctx, WaterReceiver.class);
                break;
            case "birthday":
                i = new Intent(ctx, BirthdayReceiver.class);
                break;
            case "proactive":
                i = new Intent(ctx, ProactiveReceiver.class);
                break;
            case "watchdog":
                i = new Intent(ctx, WatchdogReceiver.class);
                break;
            default:
                i = new Intent(ctx, CheckInReceiver.class);
                break;
        }
        return PendingIntent.getBroadcast(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @PluginMethod
    public void scheduleAlarm(PluginCall call) {
        Integer code = call.getInt("code");
        Double epochMs = call.getDouble("epochMs");
        String kind = call.getString("kind", "checkin");
        Integer index = call.getInt("index", 0);
        if (code == null || epochMs == null) {
            call.reject("code/epochMs wajib");
            return;
        }
        AlarmScheduler.scheduleExact(getContext(), epochMs.longValue(),
                alarmPi(kind, index == null ? 0 : index, code));
        call.resolve();
    }

    @PluginMethod
    public void cancelAlarm(PluginCall call) {
        Integer code = call.getInt("code");
        if (code == null) {
            call.reject("code wajib");
            return;
        }
        AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(alarmPiForCode(code));
        }
        call.resolve();
    }

    /** Balik dari request code -> PendingIntent (untuk cancel). */
    private PendingIntent alarmPiForCode(int code) {
        Context ctx = getContext();
        Intent i;
        if (code == 1) {
            i = new Intent(ctx, CheckInReceiver.class);
        } else if (code >= 100 && code <= 102) {
            i = new Intent(ctx, ReminderReceiver.class);
            i.putExtra("index", code - 100);
        } else if (code == 500) {
            i = new Intent(ctx, ReminderReceiver.class);
        } else if (code >= 900 && code <= 902) {
            i = new Intent(ctx, MedConfirmReceiver.class);
        } else if (code >= 1100 && code <= 1104) {
            i = new Intent(ctx, AdzanReceiver.class);
            i.putExtra("kind", "prayer");
        } else if (code == 1150) {
            i = new Intent(ctx, AdzanReceiver.class);
            i.putExtra("kind", "refresh");
        } else if (code == 1200) {
            i = new Intent(ctx, WaterReceiver.class);
        } else if (code == 1300) {
            i = new Intent(ctx, BirthdayReceiver.class);
        } else if (code == 1400) {
            i = new Intent(ctx, EscalationReceiver.class);
        } else if (code == 1500) {
            i = new Intent(ctx, WatchdogReceiver.class);
        } else if (code == 700) {
            i = new Intent(ctx, ProactiveReceiver.class);
        } else {
            i = new Intent(ctx, CheckInReceiver.class);
        }
        return PendingIntent.getBroadcast(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @PluginMethod
    public void cancelAllAlarms(PluginCall call) {
        AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            int[] codes = {1, 100, 101, 102, 500, 900, 901, 902,
                    1100, 1101, 1102, 1103, 1104, 1150, 1200, 1300, 1400, 1500, 700};
            for (int c : codes) {
                am.cancel(alarmPiForCode(c));
            }
        }
        call.resolve();
    }

    // ================= SMS / TELEPON =================

    @PluginMethod
    public void sendSms(PluginCall call) {
        String number = call.getString("number", "");
        String text = call.getString("text", "");
        JSObject r = new JSObject();
        if (number.isEmpty() || text.isEmpty()) {
            r.put("ok", false);
            r.put("error", "nomor/teks kosong");
            call.resolve(r);
            return;
        }
        if (getContext().checkSelfPermission(Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            r.put("ok", false);
            r.put("error", "izin SMS belum diberikan");
            call.resolve(r);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SmsManager sm = getContext().getSystemService(SmsManager.class);
                sm.sendTextMessage(number, null, text, null, null);
            } else {
                SmsManager.getDefault().sendTextMessage(number, null, text, null, null);
            }
            r.put("ok", true);
        } catch (Exception e) {
            r.put("ok", false);
            r.put("error", String.valueOf(e.getMessage()));
        }
        call.resolve(r);
    }

    @PluginMethod
    public void callNumber(PluginCall call) {
        String number = call.getString("number", "");
        JSObject r = new JSObject();
        if (number.isEmpty()) {
            r.put("ok", false);
            call.resolve(r);
            return;
        }
        try {
            boolean granted = getContext().checkSelfPermission(Manifest.permission.CALL_PHONE)
                    == PackageManager.PERMISSION_GRANTED;
            Intent i = new Intent(granted ? Intent.ACTION_CALL : Intent.ACTION_DIAL,
                    android.net.Uri.parse("tel:" + number));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            r.put("ok", true);
        } catch (Exception e) {
            r.put("ok", false);
            r.put("error", String.valueOf(e.getMessage()));
        }
        call.resolve(r);
    }

    @PluginMethod
    public void hasActiveSim(PluginCall call) {
        JSObject r = new JSObject();
        boolean sim = true; // fallback: jangan blokir darurat
        try {
            TelephonyManager tm = (TelephonyManager)
                    getContext().getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                if (tm.getSimState() == TelephonyManager.SIM_STATE_ABSENT) {
                    sim = false;
                }
            }
        } catch (Exception ignored) { }
        r.put("sim", sim);
        call.resolve(r);
    }

    // ================= MODE TEMAN =================

    @PluginMethod
    public void startCompanion(PluginCall call) {
        try {
            CompanionService.start(getContext());
            call.resolve();
        } catch (Exception e) {
            call.reject("gagal start: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopCompanion(PluginCall call) {
        try {
            CompanionService.stop(getContext());
            call.resolve();
        } catch (Exception e) {
            call.reject("gagal stop: " + e.getMessage());
        }
    }

    @PluginMethod
    public void isCompanionRunning(PluginCall call) {
        JSObject r = new JSObject();
        r.put("running", CompanionService.isRunning);
        call.resolve(r);
    }

    // ================= BATTERY =================

    @PluginMethod
    public void getBattery(PluginCall call) {
        JSObject r = new JSObject();
        int level = -1;
        try {
            BatteryManager bm = (BatteryManager)
                    getContext().getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Exception ignored) { }
        r.put("level", level);
        call.resolve(r);
    }

    // ================= MIKROFON =================

    @PluginMethod
    public void hasMicPermission(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED);
        call.resolve(r);
    }

    @PluginMethod
    public void requestMicPermission(PluginCall call) {
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        // pola resmi Capacitor: minta izin via alias plugin -> PermissionCallback
        requestPermissionForAlias("mic", call, "micCallback");
    }

    @PermissionCallback
    private void micRequested(PluginCall call) {
        hasMicPermission(call);
    }

    @PermissionCallback
    private void micCallback(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("mic") == PermissionState.GRANTED);
        call.resolve(r);
    }

    // ================= LOKASI =================

    @PluginMethod
    public void getGeo(PluginCall call) {
        JSObject r = new JSObject();
        try {
            boolean grantedFine = getContext()
                    .checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean grantedCoarse = getContext()
                    .checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (grantedFine || grantedCoarse) {
                LocationManager lm = (LocationManager)
                        getContext().getSystemService(Context.LOCATION_SERVICE);
                Location loc = null;
                if (lm != null) {
                    try {
                        loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    } catch (Exception ignored) { }
                    if (loc == null) {
                        try {
                            loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        } catch (Exception ignored) { }
                    }
                }
                if (loc != null) {
                    r.put("lat", loc.getLatitude());
                    r.put("lon", loc.getLongitude());
                    call.resolve(r);
                    return;
                }
            }
        } catch (Exception ignored) { }
        call.resolve(null); // JS fallback: geolocation browser / default Jakarta
    }

    // ================= NOTIFIKASI =================

    @PluginMethod
    public void requestNotificationsPermission(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && getContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            != PackageManager.PERMISSION_GRANTED) {
                getActivity().requestPermissions(
                        new String[]{ "android.permission.POST_NOTIFICATIONS" }, 52);
            }
        } catch (Exception ignored) { }
        call.resolve();
    }

    // ================= SINKRON PREFS =================
    // Format snapshot: {key: ["s"|"b"|"i"|"l"|"f", value]} — JS (localStorage) adalah
    // sumber kebenaran settings; native menulis balik state runtime (mis.
    // med_confirm_waiting) yang di-pull JS saat app dibuka.

    @PluginMethod
    public void syncPrefs(PluginCall call) {
        String data = call.getString("data", "");
        if (data.isEmpty()) {
            call.reject("data wajib");
            return;
        }
        try {
            org.json.JSONObject obj = new org.json.JSONObject(data);
            android.content.SharedPreferences.Editor ed =
                    getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                try {
                    org.json.JSONArray pair = obj.getJSONArray(k);
                    String type = pair.getString(0);
                    switch (type) {
                        case "b":
                            ed.putBoolean(k, pair.getBoolean(1));
                            break;
                        case "i":
                            ed.putInt(k, pair.getInt(1));
                            break;
                        case "l":
                            ed.putLong(k, pair.getLong(1));
                            break;
                        case "f":
                            // float (mis. last_lat/last_lon) — kode Java lain baca getFloat
                            ed.putFloat(k, (float) pair.getDouble(1));
                            break;
                        default:
                            ed.putString(k, pair.getString(1));
                            break;
                    }
                } catch (Exception keyErr) {
                    // satu key rusak jangan sampai membatalkan seluruh sinkronisasi
                }
            }
            ed.apply();
            call.resolve();
        } catch (Exception e) {
            call.reject("sync gagal: " + e.getMessage());
        }
    }

    @PluginMethod
    public void pullPrefs(PluginCall call) {
        JSObject out = new JSObject();
        try {
            Map<String, ?> all = getContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                org.json.JSONArray pair = new org.json.JSONArray();
                if (v instanceof Boolean) {
                    pair.put("b");
                    pair.put((Boolean) v);
                } else if (v instanceof Integer) {
                    pair.put("i");
                    pair.put((Integer) v);
                } else if (v instanceof Long) {
                    pair.put("l");
                    pair.put((Long) v);
                } else if (v instanceof Float) {
                    pair.put("f");
                    pair.put(((Float) v).doubleValue());
                } else {
                    pair.put("s");
                    pair.put(String.valueOf(v));
                }
                out.put(e.getKey(), pair);
            }
        } catch (Exception ignored) { }
        call.resolve(out);
    }
}
