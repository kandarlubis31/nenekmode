package com.example.nenekmode;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service "teman ngobrol": mic dengar terus walau layar mati,
 * transkripsi Whisper, jawaban streaming LLM -> TTS per kalimat,
 * auto-interrupt saat user bicara, dan semua alur ConversationEngine.
 * Aktif kalau "keep_alive" dinyalakan di pengaturan.
 */
public class CompanionService extends Service implements TextToSpeech.OnInitListener {

    private static final String TAG = "CompanionService";
    public static final String CHANNEL_ID = "companion_service";

    public static boolean isRunning = false;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private HandlerThread audioThread;
    private Handler audioHandler;
    private Handler mainHandler;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;

    private ConversationEngine engine;
    private MemoryStore memory;
    private ContentBank content;
    private NewsClient newsClient;
    private GroqStreamingClient groq;
    private IntentMatcher intentMatcher;
    private WakeWordManager wakeManager;
    private final java.util.concurrent.atomic.AtomicBoolean wakeTriggered =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private SharedPreferences prefs;

    private final Queue<String> speakQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean speaking = new AtomicBoolean(false);
    private String lastSpoken = "";
    private boolean streamFirstSentence = true;
    private boolean callInProgress = false;
    private boolean showedSimWarning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        prefs = getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        audioThread = new HandlerThread("CompanionAudio");
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());

        memory = new MemoryStore(this);
        content = new ContentBank();
        engine = new ConversationEngine(this, memory, content, this::handleEngineOutput);
        newsClient = new NewsClient();
        groq = new GroqStreamingClient(this);
        intentMatcher = new IntentMatcher(this);
        wakeManager = new WakeWordManager(this);
        if (prefs.getBoolean("vosk_enabled", false)) {
            wakeManager.ensureReady((ok, msg) ->
                    Log.i(TAG, "Vosk model: " + (ok ? "siap" : "belum siap (" + msg + ")")));
        }

        if (PrayerTimes.isEnabled(this)) {
            PrayerTimes.refreshIfNeededAsync(this, null);
        }

        startAsForeground();
        tts = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        loopListen();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped.set(true);
        isRunning = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (audioThread != null) {
            audioThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        int lang = tts.setLanguage(new Locale("id", "ID"));
        if (lang < 0) tts.setLanguage(Locale.getDefault());
        tts.setSpeechRate(0.75f);
        tts.setPitch(0.85f);
        ttsReady = true;
        showedSimWarning = prefs.getBoolean("sim_warning_shown", false);
        if (!hasActiveSim() && !showedSimWarning) {
            showedSimWarning = true;
            prefs.edit().putBoolean("sim_warning_shown", true).apply();
            speak("Perhatian, kartu SIM HP ini belum terpasang atau belum aktif. "
                    + "Panggilan darurat belum bisa jalan. Mohon diperiksa keluarga ya.");
        }

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { speaking.set(true); }
            @Override public void onDone(String utteranceId) {
                speaking.set(false);
                mainHandler.postDelayed(this::next, 300);
            }
            @Override public void onError(String utteranceId) {
                speaking.set(false);
                mainHandler.postDelayed(this::next, 300);
            }
            private void next() { speakNext(); }
        });

        // sapaan pembuka service
        String name = prefs.getString("nenek_name", "Bu");
        String asisten = prefs.getString("assistant_name", "Kandar");
        String followup = ConversationEngine.getFollowupQuestion(this, name);
        if (followup != null) {
            speak("Oh iya " + name + ", " + followup);
        } else {
            speak(asisten + " siap nemenin " + name + ". Silakan bicara kapan saja ya.");
        }
    }

    // ================= LOOP DENGAR =================

    private boolean voskEnabled() {
        return prefs.getBoolean("vosk_enabled", false);
    }

    /**
     * Idle listening pakai Vosk offline: hanya menunggu wake word (nama asisten).
     * Setelah terdengar, lanjut ke loop normal (VAD + Whisper) untuk menangkap perintah.
     * Kalau model belum siap, caller otomatis fallback ke fase 1.
     */
    private void voskIdleListen() {
        audioHandler.post(() -> {
            if (stopped.get() || busy.get()) return;
            busy.set(true);
            org.vosk.Recognizer rec = wakeManager.createRecognizer(AudioUtil.SAMPLE_RATE);
            if (rec == null) { // model gagal -> fallback fase 1 (tanpa gate)
                busy.set(false);
                mainHandler.postDelayed(this::loopListen, 300);
                return;
            }
            AudioRecord recorder = null;
            boolean heard = false;
            try {
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        AudioUtil.SAMPLE_RATE, AudioUtil.CHANNEL_CONFIG,
                        AudioUtil.AUDIO_FORMAT, AudioUtil.BUFFER_SIZE);
                if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder.startRecording();
                    byte[] buf = new byte[4096];
                    long start = System.currentTimeMillis();
                    while (!stopped.get() && System.currentTimeMillis() - start < 30000L) {
                        int n = recorder.read(buf, 0, buf.length);
                        if (n <= 0) continue;
                        if (rec.acceptWaveForm(buf, n)) {
                            if (containsWakeWord(rec.getResult())) { heard = true; break; }
                        } else if (containsWakeWord(rec.getPartialResult())) {
                            heard = true; break;
                        }
                    }
                    recorder.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "Vosk idle error: " + e.getMessage());
            } finally {
                if (recorder != null) {
                    try { recorder.release(); } catch (Exception ignored) {}
                }
                try { rec.close(); } catch (Exception ignored) {}
            }
            busy.set(false);
            if (heard) {
                wakeTriggered.set(true);
                mainHandler.post(() -> speak("Ada, " + prefs.getString("nenek_name", "Bu") + "!"));
                mainHandler.postDelayed(this::loopListen, 600);
            } else {
                mainHandler.postDelayed(this::voskIdleListen, 300);
            }
        });
    }

    private boolean containsWakeWord(String voskJson) {
        try {
            if (voskJson == null || voskJson.isEmpty()) return false;
            org.json.JSONObject o = new org.json.JSONObject(voskJson);
            String text = (o.optString("partial", "") + " " + o.optString("text", "")).toLowerCase();
            if (text.trim().isEmpty()) return false;
            String name = prefs.getString("assistant_name", "Kandar").toLowerCase();
            return text.contains(name) || text.contains("kandar") || text.contains("asisten");
        } catch (Exception e) {
            return false;
        }
    }

    private void loopListen() {
        audioHandler.post(() -> {
            if (stopped.get() || busy.get()) return;
            // wake word offline (fase 2) kalau aktif & model siap.
            // Setelah wake word kedengar, satu putaran berikutnya langsung
            // pakai loop normal (VAD+Whisper) untuk menangkap perintah.
            if (!wakeTriggered.get() && voskEnabled() && wakeManager.isLoaded()) {
                voskIdleListen();
                return;
            }
            wakeTriggered.set(false);
            busy.set(true);
            try {
                AudioUtil.StopFlag flag = stopped::get;
                byte[] pcm = AudioUtil.recordWithVad(flag);
                busy.set(false);
                if (pcm == null) {
                    mainHandler.postDelayed(this::loopListen, 300);
                    return;
                }
                handlePcm(pcm);
            } catch (Exception e) {
                Log.e(TAG, "loop error", e);
                busy.set(false);
                mainHandler.postDelayed(this::loopListen, 1000);
            }
        });
    }

    private void handlePcm(byte[] pcm) {
        if (pcm == null || pcm.length == 0) { // sinyal VAD "selesai bicara"
            EscalationManager.cancelEscalation(this);
        }
        try {
            File wav = new File(getCacheDir(), "companion_stt.wav");
            writeWav(wav, pcm);
            groq.transcribeAudio(wav, new GroqStreamingClient.TranscribeCallback() {
                @Override
                public void onSuccess(String text) {
                    wav.delete();
                    if (text == null || text.trim().isEmpty()) {
                        mainHandler.postDelayed(CompanionService.this::loopListen, 300);
                        return;
                    }
                    processTranscript(text.toLowerCase().trim());
                }

                @Override
                public void onError(String error) {
                    wav.delete();
                    mainHandler.postDelayed(CompanionService.this::loopListen, 1000);
                }
            });
        } catch (Exception e) {
            mainHandler.postDelayed(this::loopListen, 1000);
        }
    }

    private void processTranscript(String raw) {
        // tanda hidup: batalkan eskalasi cek harian
        EscalationManager.cancelEscalation(this);
        // matikan total hanya dengan perintah eksplisit
        if (raw.contains("tutup asisten") || raw.contains("matiin asisten")
                || raw.contains("matikan asisten") || raw.contains("stop mode teman")) {
            speak("Sampai jumpa " + prefs.getString("nenek_name", "Bu") + ".");
            mainHandler.postDelayed(this::stopSelf, 4000);
            return;
        }
        // berhenti = jeda denger sebentar, jangan matikan service
        if (raw.contains("berhenti") || raw.contains("stop") || raw.contains("selesai")) {
            speak("Oke, " + prefs.getString("assistant_name", "Kandar")
                    + " diam dulu sebentar. Panggil lagi kapan pun kamu mau.");
            mainHandler.postDelayed(this::loopListen, 60000);
            return;
        }

        // gerbang wake word
        if (!engine.passesWakeGate(raw)) {
            engine.notifyWakeIgnored();
            return; // notifyWakeIgnored sudah bicara, loop lanjut lewat onDone TTS
        }

        // alur lokal (kuis, doa, obat, nostalgia, lagu, berita, memori)
        if (engine.handleQuickCommands(raw)) {
            return;
        }

        // online: LLM streaming; offline: fallback intent lokal
        if (!hasInternet()) {
            IntentMatcher.MatchResult r = intentMatcher.match(raw);
            if (r.action != null && !r.action.isEmpty()) {
                handleAction(r.action);
            } else if (r.response != null && !r.response.isEmpty()) {
                speak(personalize(r.response));
            } else {
                speak("Maaf " + prefs.getString("nenek_name", "Bu")
                        + ", nggak ada internet sekarang. Coba tanya hal lain ya.");
            }
            return;
        }

        groq.getStreamingResponse(raw,
                prefs.getString("nenek_name", "Bu"),
                prefs.getString("assistant_name", "Kandar"),
                "", // intent lokal sudah ditangani matcher (hemat token)
                memory.getPromptBlock(),
                getChatHistory(),
                new GroqStreamingClient.StreamCallback() {
                    @Override
                    public void onSentence(String sentence) {
                        if (sentence.startsWith("[ACTION:")) {
                            int close = sentence.indexOf(']');
                            if (close > 0) {
                                handleAction(sentence.substring(8, close));
                                return;
                            }
                        }
                        if (streamFirstSentence) {
                            streamFirstSentence = false;
                            speak(sentence);
                        } else {
                            queueSentence(sentence);
                        }
                    }

                    @Override
                    public void onDone(String fullText) {
                        streamFirstSentence = true;
                        engine.onAssistantResponse(raw, lastSpoken);
                    }

                    @Override
                    public void onError(String error) {
                        streamFirstSentence = true;
                        // fallback intent offline
                        IntentMatcher.MatchResult r = intentMatcher.match(raw);
                        if (r.action != null && !r.action.isEmpty()) {
                            handleAction(r.action);
                        } else if (r.response != null && !r.response.isEmpty()) {
                            speak(personalize(r.response));
                        } else {
                            speak(error);
                        }
                    }
                });
    }

    private void handleAction(String action) {
        switch (action) {
            case "get_greeting": {
                String n = prefs.getString("nenek_name", "Bu");
                String tod = ConversationEngine.getTimeOfDay();
                speak("Selamat " + tod + " " + n + "! Sehat-sehat aja kan?");
                break;
            }
            case "get_time": {
                java.text.SimpleDateFormat hh = new java.text.SimpleDateFormat("HH", Locale.getDefault());
                java.text.SimpleDateFormat mm = new java.text.SimpleDateFormat("mm", Locale.getDefault());
                int h = Integer.parseInt(hh.format(new java.util.Date()));
                int m = Integer.parseInt(mm.format(new java.util.Date()));
                String tod = ConversationEngine.getTimeOfDay();
                int dh = h > 12 ? h - 12 : (h == 0 ? 12 : h);
                String mStr = m > 0 ? " lewat " + m + " menit" : "";
                speak("Sekarang jam " + dh + mStr + " " + tod + " ya " + prefs.getString("nenek_name", "Bu") + ".");
                break;
            }
            case "get_date": {
                String d = new java.text.SimpleDateFormat("EEEE, dd MMMM yyyy", new Locale("id", "ID"))
                        .format(new java.util.Date());
                speak("Hari ini " + d + " ya " + prefs.getString("nenek_name", "Bu") + ".");
                break;
            }
            case "repeat_last":
                speak(lastSpoken.isEmpty() ? "Belum ada obrolan tadi." : lastSpoken);
                break;
            case "get_weather":
            case "get_forecast":
                speak("Untuk cuaca, buka dulu aplikasinya ya, fitur cuaca jalan di layar utama.");
                break;
            case "get_health":
                handleMedStatus();
                break;
            case "get_prayer_times":
                speak(PrayerTimes.scheduleSpeech(this, prefs.getString("nenek_name", "Bu")));
                break;
            case "get_next_prayer":
                speak(PrayerTimes.nextPrayerSpeech(this, prefs.getString("nenek_name", "Bu")));
                break;
            case "get_help":
                speak("Bisa tanya jam, tanggal, bilang mulai kuis, cerita dulu, atau minta doa. "
                        + "Bilang berita buat dengar kabar terbaru, atau puter lagu kalau mau dengar musik.");
                break;
            case "get_battery":
                android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
                int lvl = bm != null ? bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
                speak(lvl < 0 ? "Baterai nggak bisa dibaca." :
                        "Baterai sisa " + lvl + " persen." + (lvl <= 20 ? " Segera dicharge ya." : ""));
                break;
            case "get_favorite_contact":
                speak("Buat nelpon kontak favorit, buka aplikasinya dulu ya, biar aman.");
                break;
            case "trigger_emergency":
                speak("Baik, tenang. Aku hubungkan bantuan sekarang.");
                // diproses lewat app utama bila terbuka; di service cukup SMS
                sendEmergencySms();
                break;
            default:
                speak("Oke.");
            }
    }

    /** Output engine: marker dieksekusi (YouTube/berita), sisanya dibacakan. */
    private void handleEngineOutput(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (text.startsWith(ConversationEngine.MUSIC_MARKER)) {
            String query = text.substring(ConversationEngine.MUSIC_MARKER.length()).trim();
            String clean = query == null ? "" : query
                    .replaceAll("puterin|puter|putar|dengerin|musik|lagu", "").trim();
            if (clean.isEmpty()) clean = "lagu nostalgia indonesia";
            speak("Oke, lagunya aku carikan di YouTube ya. Mau dengerin yang mana pilih aja.");
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query="
                                + Uri.encode(clean)));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                speak("Gagal buka YouTube.");
            }
            return;
        }
        if (text.trim().equals(ConversationEngine.NEWS_MARKER)) {
            speak("Sebentar ya, aku ambil beritanya dulu.");
            new NewsClient().getTopHeadlines(new NewsClient.NewsCallback() {
                @Override
                public void onSuccess(java.util.List<String> headlines) {
                    mainHandler.post(() -> {
                        StringBuilder sb = new StringBuilder("Ini berita hari ini. ");
                        int n = Math.min(3, headlines.size());
                        for (int i = 0; i < n; i++) {
                            sb.append("Berita ").append(i + 1).append(": ")
                              .append(headlines.get(i)).append(". ");
                        }
                        speak(sb.toString());
                    });
                }

                @Override
                public void onError(String error) {
                    mainHandler.post(() -> speak("Beritanya lagi gak bisa diambil. Coba lagi nanti ya."));
                }
            });
            return;
        }
        speak(text);
    }

    private void handleMedStatus() {
        SharedPreferences p = prefs;
        org.json.JSONArray times = ReminderReceiver.getMedTimes(p);
        StringBuilder sb = new StringBuilder("Jadwal obat: ");
        boolean any = false;
        for (int i = 0; i < times.length(); i++) {
            try {
                org.json.JSONObject t = times.getJSONObject(i);
                if (!t.optBoolean("en", true)) continue;
                if (any) sb.append(", ");
                sb.append(String.format("%02d:%02d", t.getInt("h"), t.getInt("m")));
                any = true;
            } catch (Exception ignored) {}
        }
        if (!any) sb.append("belum ada yang aktif");
        else sb.append(". Jangan lupa ya.");

        // catatan tensi darah terakhir (kalau fiturnya aktif)
        if (prefs.getBoolean("bp_enabled", false)) {
            BloodPressureStore.Entry bp = BloodPressureStore.latest(this);
            if (bp != null) {
                sb.append(" Tensi darah terakhir ").append(bp.sys).append(" atas ").append(bp.dia)
                  .append(".");
                if (bp.sys >= 140 || bp.dia >= 90) {
                    sb.append(" Agak tinggi, jangan lupa kontrol ya.");
                } else {
                    sb.append(" Masih bagus.");
                }
            } else {
                sb.append(" Belum ada catatan tensi darah.");
            }
            if (BloodPressureStore.count(this) > 1) {
                sb.append(" ").append(BloodPressureStore.speechHistory(this,
                        prefs.getString("nenek_name", "Bu")));
            }
        }

        // streak minum obat tepat waktu
        int streak = prefs.getInt("med_streak", 0);
        if (streak >= 3) {
            sb.append(" ").append(streak).append(" hari berturut-turut minum obat tepat waktu, hebat!");
        }

        speak(sb.toString());
    }

    private void sendEmergencySms() {
        String number = prefs.getString("emergency_number", "");
        if (number.isEmpty()) return;
        if (checkSelfPermission(android.Manifest.permission.SEND_SMS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (!hasActiveSim()) return;
        try {
            android.telephony.SmsManager.getDefault().sendTextMessage(number, null,
                    "Darurat! " + prefs.getString("nenek_name", "Bu") + " membutuhkan bantuan!",
                    null, null);
        } catch (Exception ignored) {}
    }

    private String personalize(String s) {
        return s.replace("{nenek_name}", prefs.getString("nenek_name", "Bu"))
                .replace("{assistant_name}", prefs.getString("assistant_name", "Kandar"));
    }

    /** Deteksi SIM aktif; fallback aman = true biar darurat gak keblokir gara-gara error deteksi. */
    private boolean hasActiveSim() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return true;
            if (tm.getSimState() == TelephonyManager.SIM_STATE_ABSENT) return false;
            String number = tm.getLine1Number();
            return number != null && !number.trim().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    private java.util.List<String> getChatHistory() {
        java.util.List<String> history = new java.util.ArrayList<>();
        String saved = prefs.getString("chat_history", "");
        for (String line : saved.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                int idx = t.indexOf("] ");
                history.add(idx >= 0 ? t.substring(idx + 2) : t);
            }
        }
        return history;
    }

    // ================= TTS =================

    private void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        mainHandler.post(() -> {
            // potong TTS sebelumnya (auto-interrupt natural saat respons baru datang)
            speakQueue.clear();
            lastSpoken = text;
            if (tts != null && ttsReady) {
                // mode malam: volume TTS lebih pelan (30%)
                android.os.Bundle ttsParams = new android.os.Bundle();
                if (ConversationEngine.isNightMode(this)) {
                    ttsParams.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.3f);
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, ttsParams, "COMP_" + System.currentTimeMillis());
            } else {
                // TTS belum siap: jangan sampai loop dengar mati selamanya
                mainHandler.postDelayed(this::loopListen, 2000);
            }
        });
    }

    /** Kalimat lanjutan streaming: masuk antrean tanpa memotong yang sedang bicara. */
    private void queueSentence(String text) {
        if (text == null || text.trim().isEmpty()) return;
        mainHandler.post(() -> {
            speakQueue.add(text);
            lastSpoken = text;
            if (tts != null && ttsReady && !tts.isSpeaking() && speaking.get() == false) {
                speakNext();
            }
        });
    }

    private void speakNext() {
        String next = speakQueue.poll();
        if (next == null) {
            // selesai bicara -> dengar lagi
            loopListen();
            return;
        }
        if (tts != null && ttsReady) {
            android.os.Bundle ttsParams = new android.os.Bundle();
            if (ConversationEngine.isNightMode(this)) {
                ttsParams.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.3f);
            }
            tts.speak(next, TextToSpeech.QUEUE_FLUSH, ttsParams, "COMP_" + System.currentTimeMillis());
        }
    }

    // ================= HELPERS =================

    private boolean hasInternet() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnectedOrConnecting();
    }

    private void writeWav(File file, byte[] pcm) throws Exception {
        int dataSize = pcm.length;
        int fileSize = dataSize + 36;
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write("RIFF".getBytes());
            fos.write(intBytes(fileSize));
            fos.write("WAVE".getBytes());
            fos.write("fmt ".getBytes());
            fos.write(intBytes(16));
            fos.write(new byte[]{1, 0});
            fos.write(new byte[]{1, 0});
            fos.write(intBytes(AudioUtil.SAMPLE_RATE));
            fos.write(intBytes(AudioUtil.SAMPLE_RATE * 2));
            fos.write(new byte[]{2, 0});
            fos.write(new byte[]{16, 0});
            fos.write("data".getBytes());
            fos.write(intBytes(dataSize));
            fos.write(pcm);
        }
    }

    private byte[] intBytes(int v) {
        return new byte[]{
                (byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)
        };
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Teman Ngobrol",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Menjaga asisten tetap mendengarkan");
            nm.createNotificationChannel(ch);
        }

        Intent openApp = new Intent(this, com.example.nenekmode.web.MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification.Builder notif;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notif = new android.app.Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle("Baachan aktif")
                    .setContentText("Asisten siap dengar kapan pun. Sentuh untuk buka.")
                    .setOngoing(true)
                    .setContentIntent(pi);
        } else {
            notif = new android.app.Notification.Builder(this)
                    .setSmallIcon(R.drawable.logo)
                    .setContentTitle("Baachan aktif")
                    .setContentText("Asisten siap dengar kapan pun. Sentuh untuk buka.")
                    .setOngoing(true)
                    .setContentIntent(pi);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, notif.build());
        }
    }

    /** Helper buat start service dari Activity dengan aman. */
    public static void start(Context context) {
        WatchdogReceiver.scheduleNext(context); // watchdog ikut nyala tiap Mode Teman aktif
        Intent i = new Intent(context, CompanionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, CompanionService.class);
        i.setAction("STOP");
        context.startService(i);
    }
}
