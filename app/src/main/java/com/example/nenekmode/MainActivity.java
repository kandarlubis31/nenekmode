package com.example.nenekmode;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private enum State { IDLE, LISTENING, SPEAKING, PROCESSING }
    private State appState = State.IDLE;
    private FusedLocationProviderClient fusedLocationClient;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean hasGreeted = false;

    private LinearLayout statusCard;
    private TextView statusText;
    private TextView micIcon;
    private TextView statusSub;
    private LinearLayout chatContainer;
    private TextView assistantGreeting;
    private TextView checkInStatus;
    private ScrollView scrollView;
    private float chatTextSize = 16f;
    private Button emergencyButton;
    private Button btnHealth;
    private Button btnWeather;
    private Button btnCompanion;
    private Button btnSettings;

    private long lastPanicTime = 0;
    private static final float PANIC_THRESHOLD_DB = 30.0f;
    private static final long PANIC_COOLDOWN_MS = 8000L;

    private SharedPreferences prefs;
    private static final String PREFS_NAME = "Nenek_Assistant";
    private Handler handler;
    private Handler audioHandler;
    private HandlerThread audioThread;

    private String nenek_name = "Bu";
    private String assistant_name = "Kandar";
    private String lastSpokenText = "";

    private IntentMatcher intentMatcher;
    private WeatherClient weatherClient;
    private GroqClient groqClient;
    private MemoryStore memoryStore;
    private ConversationEngine engine;
    private Button btnBoard;

    private AudioManager audioManager;
    private int originalVolume = -1;

    private boolean highContrastMode = false;
    private int fontSizeScale = 1;
    private boolean reminderEnabled = false;
    private boolean checkInEnabled = false;
    private int checkInHour = 9;
    private int checkInMinute = 0;
    private static final int FONT_SIZE_NORMAL = 1;
    private static final int FONT_SIZE_LARGE = 2;
    private static final int FONT_SIZE_XLARGE = 3;

    private AtomicBoolean isStopped = new AtomicBoolean(false);
    private File tempAudioFile;

    private String favoriteContactNumber = "";
    private String favoriteContactName = "";
    private int lastBatteryLevel = 100;
    private boolean batteryWarningSpoken = false;
    private boolean callInProgress = false;
    private boolean showedSimWarning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ReminderReceiver.createNotificationChannel(this);
        CheckInReceiver.createNotificationChannel(this);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        statusCard = findViewById(R.id.statusCard);
        statusText = findViewById(R.id.statusText);
        micIcon = findViewById(R.id.micIcon);
        statusSub = findViewById(R.id.statusSub);
        chatContainer = findViewById(R.id.chatContainer);
        assistantGreeting = findViewById(R.id.assistantGreeting);
        checkInStatus = findViewById(R.id.checkInStatus);
        scrollView = findViewById(R.id.scrollView);
        emergencyButton = findViewById(R.id.emergencyButton);
        btnHealth = findViewById(R.id.btnHealth);
        btnWeather = findViewById(R.id.btnWeather);
        btnCompanion = findViewById(R.id.btnCompanion);
        btnCompanion = findViewById(R.id.btnCompanion);
        btnSettings = findViewById(R.id.btnSettings);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        audioThread = new HandlerThread("AudioThread");
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());

        nenek_name = prefs.getString("nenek_name", "Bu");
        assistant_name = prefs.getString("assistant_name", "Kandar");
        highContrastMode = prefs.getBoolean("high_contrast", false);
        fontSizeScale = prefs.getInt("font_size", 1);
        reminderEnabled = prefs.getBoolean("reminder_enabled", false);
        checkInEnabled = prefs.getBoolean("checkin_enabled", false);
        checkInHour = prefs.getInt("checkin_hour", 9);
        checkInMinute = prefs.getInt("checkin_minute", 0);
        favoriteContactNumber = prefs.getString("favorite_contact_number", "");
        favoriteContactName = prefs.getString("favorite_contact_name", "");
        showedSimWarning = prefs.getBoolean("sim_warning_shown", false);

        applyAccessibilitySettings();
        updateAssistantGreeting();

        weatherClient = new WeatherClient();
        intentMatcher = new IntentMatcher(this);
        groqClient = new GroqClient(this);
        memoryStore = new MemoryStore(this);
        engine = new ConversationEngine(this, memoryStore, new ContentBank(),
                this::handleEngineOutput);

        tempAudioFile = new File(getCacheDir(), "temp_whisper.wav");

        renderSavedHistory();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
        }

        btnBoard = findViewById(R.id.btnBoard);
        btnBoard.setOnClickListener(v ->
                startActivity(new Intent(this, BoardActivity.class)));
        WatchdogReceiver.scheduleNext(this);

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // haptic feedback: lansia perlu feel "tombolnya ke-tekan"
        setupHaptics(btnHealth, btnWeather, btnCompanion, btnBoard, btnSettings, emergencyButton);

        emergencyButton.setOnClickListener(v -> callEmergency());
        btnHealth.setOnClickListener(v -> handleHealthButton());
        btnWeather.setOnClickListener(v -> handleWeatherButton());
        btnCompanion.setOnClickListener(v -> {
            boolean on = prefs.getBoolean("keep_alive", false);
            if (on) {
                CompanionService.stop(this);
                prefs.edit().putBoolean("keep_alive", false).apply();
                btnCompanion.setText("Mode Teman: OFF");
                Toast.makeText(this, "Mode teman dimatikan", Toast.LENGTH_SHORT).show();
            } else {
                prefs.edit().putBoolean("keep_alive", true).apply();
                CompanionService.start(this);
                btnCompanion.setText("Mode Teman: ON");
                Toast.makeText(this, "Mode teman aktif - asisten dengar terus", Toast.LENGTH_SHORT).show();
            }
        });

        // buka app utama = papan info nonaktif lagi (aktifkan ulang lewat tombol)
        prefs.edit().putBoolean("board_mode", false).apply();

        boolean keepAlive = prefs.getBoolean("keep_alive", false);
        btnCompanion.setText(keepAlive ? "Mode Teman: ON" : "Mode Teman: OFF");
        if (keepAlive && !CompanionService.isRunning) {
            CompanionService.start(this);
        }

        if (reminderEnabled) {
            ReminderReceiver.setReminders(this);
        }
        ProactiveReceiver.rescheduleNext(this);
        if (PrayerTimes.isEnabled(this)) {
            PrayerTimes.refreshIfNeededAsync(this, null);
            saveLocationForAdzan();
        }
        if (prefs.getBoolean("water_enabled", false)) {
            WaterReceiver.scheduleNext(this);
        }
        if (prefs.getBoolean("birthday_enabled", false)) {
            BirthdayReceiver.scheduleNext(this);
        }
        if (checkInEnabled) {
            CheckInReceiver.setCheckIn(this, checkInHour, checkInMinute);
            updateCheckInStatus();
        }

        tts = new TextToSpeech(this, this);

        String[] permissions = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS
        };

        if (hasAllPermissions(new String[]{ Manifest.permission.RECORD_AUDIO })) {
            startWhisperListening();
        } else {
            ActivityCompat.requestPermissions(this, permissions, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appState == State.IDLE && ttsReady && hasGreeted && !isRecordingActive()) {
            scheduleListening(500);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
        audioHandler.removeCallbacksAndMessages(null);
        stopWhisperRecording();
        appState = State.IDLE;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        audioHandler.removeCallbacksAndMessages(null);
        stopWhisperRecording();
        cleanTempFile();

        if (audioThread != null) {
            audioThread.quitSafely();
            try { audioThread.join(500); } catch (InterruptedException ignored) {}
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }

        if (audioManager != null && originalVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
        }

        super.onDestroy();
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            updateStatusUI("TTS gagal");
            return;
        }

        int langResult = tts.setLanguage(new Locale("id", "ID"));
        if (langResult < 0) {
            tts.setLanguage(Locale.getDefault());
        }
        tts.setSpeechRate(0.75f);
        tts.setPitch(0.85f);
        ttsReady = true;

        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {}

            @Override
            public void onDone(String utteranceId) {
                handler.post(() -> {
                    tts.setPitch(0.85f);
                    tts.setSpeechRate(0.75f);
                    appState = State.IDLE;
                    if (hasGreeted) {
                        scheduleListening(300);
                    }
                });
            }

            @Override
            public void onError(String utteranceId) {
                handler.post(() -> {
                    appState = State.IDLE;
                    if (hasGreeted) {
                        scheduleListening(300);
                    }
                });
            }
        });

        if (!hasGreeted) {
            hasGreeted = true;
            handler.postDelayed(() -> {
                boolean isFirstTime = prefs.getBoolean("is_first_time", true);
                if (isFirstTime) {
                    // wizard setup 4 langkah menggantikan tutorial teks
                    startActivity(new Intent(this, SetupWizardActivity.class));
                    prefs.edit().putBoolean("is_first_time", false).apply();
                } else {
                    String greetingText = ConversationEngine.getFollowupQuestion(this, nenek_name);
                    if (greetingText != null) {
                        greetingText = "Oh iya " + nenek_name + ", " + greetingText;
                    } else {
                        greetingText = "Halo " + nenek_name + ", " + assistant_name + " sudah siap temani ngobrol nih. Silakan bicara.";
                    }
                    addToHistory(assistant_name + ": " + greetingText);
                    speakResponse(greetingText, 0.85f, 0.75f);
                }
                checkBatteryLevel();
                prefs.edit().putBoolean("is_first_time", false).apply();
            }, 1000);
        }
    }

    private void speakResponse(String text, float pitch, float rate) {
        if (tts == null || !ttsReady) return;

        stopWhisperRecording();
        handler.removeCallbacksAndMessages(null);

        appState = State.SPEAKING;
        updateStatusUI("Menjawab...");

        lastSpokenText = text;
        tts.setPitch(pitch);
        tts.setSpeechRate(rate);
        // mode malam: volume TTS lebih pelan (30%)
        android.os.Bundle ttsParams = new android.os.Bundle();
        if (ConversationEngine.isNightMode(this)) {
            ttsParams.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.3f);
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, ttsParams, "TTS_" + System.currentTimeMillis());
    }

    /** Info kesehatan untuk tombol: tensi terakhir + riwayat + streak minum obat. */
    private String getHealthAnnouncement() {
        String bp = BloodPressureStore.speechLast(this, nenek_name);
        if (BloodPressureStore.count(this) > 1) {
            bp += " " + BloodPressureStore.speechHistory(this, nenek_name);
        }
        return bp;
    }

    private void handleStopCommand() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }
        stopWhisperRecording();
        handler.removeCallbacksAndMessages(null);
        appState = State.IDLE;
        addToHistory(assistant_name + ": Sampai jumpa, " + nenek_name + ".");
        if (tts != null && ttsReady) {
            appState = State.SPEAKING;
            tts.speak("Sampai jumpa, " + nenek_name + ". Aku di sini kalau butuh ya.", TextToSpeech.QUEUE_FLUSH, null, "TTS_STOP");
        }
    }

    private void handleRepeatCommand() {
        if (lastSpokenText.isEmpty()) {
            processLocalResponse("Belum ada obrolan nih " + nenek_name + ".");
            return;
        }
        speakResponse(lastSpokenText, 0.85f, 0.75f);
    }

    private boolean isRecordingActive() {
        return audioThread != null && audioThread.isAlive() && appState == State.LISTENING;
    }

    private void scheduleListening(long delayMs) {
        handler.postDelayed(() -> {
            if (appState == State.IDLE && ttsReady && hasGreeted) {
                startWhisperListening();
            }
        }, delayMs);
    }

    private void startWhisperListening() {
        if (appState == State.SPEAKING || CompanionService.isRunning) return;
        if (!hasAllPermissions(new String[]{ Manifest.permission.RECORD_AUDIO })) return;

        appState = State.LISTENING;
        updateStatusUI("Mendengarkan...");
        isStopped.set(false);

        audioHandler.post(() -> {
            byte[] pcm = AudioUtil.recordWithVad(isStopped::get);
            boolean wasStopped = isStopped.get();
            isStopped.set(false);

            if (pcm == null) {
                final boolean stoppedByUser = wasStopped;
                handler.post(() -> {
                    if (!stoppedByUser && appState == State.LISTENING) {
                        appState = State.IDLE;
                        scheduleListening(300);
                    } else if (stoppedByUser) {
                        appState = State.IDLE;
                    }
                });
                return;
            }

            try {
                writeWavFile(tempAudioFile, pcm);
            } catch (Exception e) {
                handler.post(() -> {
                    appState = State.IDLE;
                    scheduleListening(1000);
                });
                return;
            }

            handler.post(() -> updateStatusUI("Mengenali suara..."));

            groqClient.transcribeAudio(tempAudioFile, new GroqClient.GroqCallback() {
                @Override
                public void onSuccess(String text) {
                    handler.post(() -> {
                        appState = State.IDLE;
                        processWhisperResult(text);
                    });
                }

                @Override
                public void onError(String error) {
                    handler.post(() -> {
                        appState = State.IDLE;
                        updateStatusUI("Gagal mengenali suara");
                        scheduleListening(1000);
                    });
                }
            });
        });
    }

    private void stopWhisperRecording() {
        isStopped.set(true);
    }

    private void processWhisperResult(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            scheduleListening(300);
            return;
        }

        String raw = rawText.toLowerCase().trim();
        addToHistory("Anda: " + raw);

        // tanda hidup: batalkan eskalasi cek harian
        EscalationManager.cancelEscalation(this);

        if (raw.contains("berhenti") || raw.contains("stop") || raw.contains("selesai")) {
            handleStopCommand();
            return;
        }
        if (raw.contains("ulangi") || raw.contains("ulang")) {
            handleRepeatCommand();
            return;
        }
        if (raw.contains("telpon") || raw.contains("telepon") || raw.contains("telfon") || raw.contains("call") || raw.contains("nelpon")) {
            if (raw.contains("anak") || raw.contains("cucu") || raw.contains("favorit") || raw.contains("kesayangan")) {
                handleFavoriteContact();
                return;
            }
        }
        if (raw.contains("baterai") || raw.contains("battery")) {
            String batteryMsg = "Baterai HP " + nenek_name + " sekarang " + lastBatteryLevel + " persen.";
            if (lastBatteryLevel <= 20) {
                batteryMsg += " Sudah rendah, sebaiknya segera dicharge.";
            } else if (lastBatteryLevel <= 50) {
                batteryMsg += " Masih cukup.";
            } else {
                batteryMsg += " Aman.";
            }
            processLocalResponse(batteryMsg);
            return;
        }

        // alur companion lokal (kuis, doa, nostalgia, obat, lagu, berita, memori)
        if (engine.handleQuickCommands(raw)) {
            return;
        }

        if (!checkInternetConnection()) {
            IntentMatcher.MatchResult localResult = intentMatcher.match(raw);
            if (localResult.action != null && !localResult.action.isEmpty()) {
                handleLocalAction(localResult.action);
            } else if (localResult.response != null && !localResult.response.isEmpty()) {
                String localResponse = localResult.response.replace("{nenek_name}", nenek_name)
                        .replace("{assistant_name}", assistant_name);
                processLocalResponse(localResponse);
            } else {
                processLocalResponse("Maaf " + nenek_name + ", tidak ada koneksi internet. Coba tanya hal lain.");
            }
            return;
        }

        groqClient.getResponse(raw, nenek_name, assistant_name,
                intentMatcher.getJsonString(), memoryStore.getPromptBlock(), getChatHistoryList(),
                new GroqClient.GroqCallback() {
                    @Override
                    public void onSuccess(String response) {
                        handler.post(() -> {
                            if (response.startsWith("[ACTION:")) {
                                String action = response.substring(8, response.indexOf("]"));
                                handleLocalAction(action);
                            } else {
                                processLocalResponse(response);
                                engine.onAssistantResponse(raw, response);
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        handler.post(() -> {
                            IntentMatcher.MatchResult result = intentMatcher.match(raw);
                            if (result.action != null && !result.action.isEmpty()) {
                                handleLocalAction(result.action);
                            } else {
                                String finalResponse = result.response;
                                if (finalResponse != null && !finalResponse.isEmpty()) {
                                    finalResponse = finalResponse.replace("{nenek_name}", nenek_name)
                                            .replace("{assistant_name}", assistant_name);
                                    processLocalResponse(finalResponse);
                                } else {
                                    processLocalResponse("Maaf " + nenek_name + ", " + assistant_name + " kurang paham. Bisa cerita lebih detail?");
                                }
                            }
                        });
                    }
                });
    }

    private List<String> getChatHistoryList() {
        List<String> history = new ArrayList<>();
        String savedHistory = prefs.getString("chat_history", "");
        if (!savedHistory.isEmpty()) {
            String[] lines = savedHistory.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    int idx = trimmed.indexOf("] ");
                    history.add(idx >= 0 ? trimmed.substring(idx + 2).trim() : trimmed);
                }
            }
        }
        return history;
    }

    private void fetchWeatherWithLocation(boolean isForecast) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            processLocalResponse("Maaf " + nenek_name + ", " + assistant_name + " belum dikasih izin buat cek lokasi HP ini.");
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            if (location != null) {
                double lat = location.getLatitude();
                double lon = location.getLongitude();
                // simpan koordinat terakhir buat jadwal adzan (Aladhan)
                prefs.edit().putFloat("last_lat", (float) lat)
                        .putFloat("last_lon", (float) lon).apply();
                updateStatusUI("Cek cuaca...");

                if (isForecast) {
                    weatherClient.getForecast(lat, lon, new WeatherClient.WeatherCallback() {
                        @Override public void onSuccess(String r) { processLocalResponse(r); }
                        @Override public void onError(String e) { processLocalResponse(e); }
                    });
                } else {
                    weatherClient.getCurrentWeather(lat, lon, new WeatherClient.WeatherCallback() {
                        @Override public void onSuccess(String r) { processLocalResponse(r); }
                        @Override public void onError(String e) { processLocalResponse(e); }
                    });
                }
            } else {
                updateStatusUI("GPS gagal, pakai lokasi default...");
                if (isForecast) {
                    weatherClient.getForecast(-6.2838, 106.8049, new WeatherClient.WeatherCallback() {
                        @Override public void onSuccess(String r) { processLocalResponse(r); }
                        @Override public void onError(String e) { processLocalResponse(e); }
                    });
                } else {
                    weatherClient.getCurrentWeather(-6.2838, 106.8049, new WeatherClient.WeatherCallback() {
                        @Override public void onSuccess(String r) { processLocalResponse(r); }
                        @Override public void onError(String e) { processLocalResponse(e); }
                    });
                }
            }
        });
    }

    private void handleLocalAction(String action) {
        switch (action) {
            case "get_greeting":
                processLocalResponse(getGreeting());
                break;
            case "get_time":
                processLocalResponse(getTime());
                break;
            case "get_date":
                processLocalResponse(getDate());
                break;
            case "repeat_last":
                String last = lastSpokenText == null ? "" : lastSpokenText;
                processLocalResponse(last.isEmpty() ? "Belum ada obrolan nih " + nenek_name + "." : last);
                break;
            case "get_weather":
                updateStatusUI("Mencari lokasi...");
                fetchWeatherWithLocation(false);
                break;
            case "get_forecast":
                updateStatusUI("Mencari lokasi...");
                fetchWeatherWithLocation(true);
                break;
            case "trigger_emergency":
                handlePanicSituation();
                break;
            case "get_health":
                handleHealthButton();
                break;
            case "get_prayer_times":
                processLocalResponse(PrayerTimes.scheduleSpeech(this, nenek_name));
                break;
            case "get_next_prayer":
                processLocalResponse(PrayerTimes.nextPrayerSpeech(this, nenek_name));
                break;
            case "get_help":
                processLocalResponse("Bisa tanya jam berapa, cuaca, tanggal, baterai, bilang mulai kuis, bilang cerita dulu, minta doa, atau minta putar lagu. Bilang berita buat dengar kabar. Bilang berhenti buat selesai.");
                break;
            case "get_battery":
                String batteryMsg = "Baterai " + lastBatteryLevel + " persen.";
                if (lastBatteryLevel <= 20) batteryMsg += " Sudah rendah, segera dicharge.";
                processLocalResponse(batteryMsg);
                break;
            case "get_favorite_contact":
                handleFavoriteContact();
                break;
            default:
                processLocalResponse("Oke " + nenek_name + ".");
                break;
        }
    }

    /**
     * Output dari ConversationEngine: marker khusus dieksekusi (YouTube/berita),
     * sisanya dibacakan.
     */
    private void handleEngineOutput(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (text.startsWith(ConversationEngine.MUSIC_MARKER)) {
            String query = text.substring(ConversationEngine.MUSIC_MARKER.length()).trim();
            String clean = query == null ? "" : query
                    .replaceAll("puterin|puter|putar|dengerin|musik|lagu", "").trim();
            if (clean.isEmpty()) clean = "lagu nostalgia indonesia";
            String search = Uri.encode(clean);
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + search));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                processLocalResponse("Oke " + nenek_name + ", lagunya aku carikan di YouTube ya. Mau dengerin yang mana pilih aja.");
            } catch (Exception e) {
                processLocalResponse("Maaf " + nenek_name + ", gagal buka YouTube. Coba lagi ya.");
            }
            return;
        }
        if (text.trim().equals(ConversationEngine.NEWS_MARKER)) {
            announceNews();
            return;
        }
        processLocalResponse(text);
    }

    private void announceNews() {
        updateStatusUI("Ambil berita...");
        processLocalResponse("Sebentar ya " + nenek_name + ", aku ambil beritanya dulu.");
        new NewsClient().getTopHeadlines(new NewsClient.NewsCallback() {
            @Override
            public void onSuccess(List<String> headlines) {
                handler.post(() -> {
                    StringBuilder sb = new StringBuilder("Ini berita hari ini ");
                    sb.append(nenek_name).append(". ");
                    int n = Math.min(3, headlines.size());
                    for (int i = 0; i < n; i++) {
                        sb.append("Berita ").append(i + 1).append(": ")
                          .append(headlines.get(i)).append(". ");
                    }
                    sb.append("Gimana " + nenek_name + ", ada yang mau diceritain lebih?");
                    addToHistory(assistant_name + ": [Berita]");
                    speakResponse(sb.toString(), 0.85f, 0.75f);
                });
            }

            @Override
            public void onError(String error) {
                handler.post(() -> processLocalResponse("Maaf " + nenek_name + ", beritanya lagi gak bisa diambil. Coba lagi nanti ya."));
            }
        });
    }

    private void processLocalResponse(String response) {
        if (response == null || response.trim().isEmpty()) return;
        handler.post(() -> {
            addToHistory(assistant_name + ": " + response);
            speakResponse(response, 0.85f, 0.75f);
        });
    }

    private void handlePanicSituation() {
        addToHistory("Sistem mendeteksi situasi darurat!");
        updateStatusUI("MENDETEKSI KEPANIKAN!");

        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE));
        }

        String panicMessage = nenek_name + " kenapa? Jangan khawatir, " + assistant_name + " ada kok. "
                + assistant_name + " akan hubungi yang lain sekarang juga.";
        speakResponse(panicMessage, 1.15f, 0.85f);

        String emergencyNumber = prefs.getString("emergency_number", "");
        if (!emergencyNumber.trim().isEmpty()) {
            handler.postDelayed(this::callEmergency, 3000);
        }
        sendEmergencySMS();
    }

    private String getGreeting() {
        int hour = Integer.parseInt(new SimpleDateFormat("HH", Locale.getDefault()).format(new Date()));
        if (hour >= 4  && hour < 11) return "Selamat pagi "  + nenek_name + "! Sehat-sehat aja?";
        if (hour >= 11 && hour < 15) return "Selamat siang " + nenek_name + "! Gimana hari " + nenek_name + " hari ini?";
        if (hour >= 15 && hour < 18) return "Selamat sore "  + nenek_name + "! Udah sore nih, udah istirahat?";
        return "Selamat malam " + nenek_name + "! Semoga nyenyak tidurnya nanti.";
    }

    private String getTime() {
        Date now    = new Date();
        int hour    = Integer.parseInt(new SimpleDateFormat("HH", Locale.getDefault()).format(now));
        int minute  = Integer.parseInt(new SimpleDateFormat("mm", Locale.getDefault()).format(now));
        String tod  = ConversationEngine.getTimeOfDay();
        int dh      = hour > 12 ? hour - 12 : (hour == 0 ? 12 : hour);
        String mStr = minute > 0 ? " lewat " + minute + " menit" : "";
        return "Sekarang jam " + dh + mStr + " " + tod + " ya " + nenek_name + ".";
    }

    private String getDate() {
        Date now    = new Date();
        String day  = new SimpleDateFormat("EEEE",         new Locale("id","ID")).format(now);
        String date = new SimpleDateFormat("dd MMMM yyyy", new Locale("id","ID")).format(now);
        return "Hari ini hari " + day + ", tanggalnya " + date + " " + nenek_name + ".";
    }

    private void addToHistory(String message) {
        handler.post(() -> {
            String ts = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            String entry = "[" + ts + "] " + message;
            addBubble(entry);
            appendHistoryLog(entry);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void appendHistoryLog(String entry) {
        StringBuilder sb = new StringBuilder(prefs.getString("chat_history", ""));
        if (sb.length() > 0) sb.append("\n");
        sb.append(entry);
        prefs.edit().putString("chat_history", sb.toString()).apply();
    }

    /** Muat ulang riwayat tersimpan sebagai bubble saat app dibuka. */
    private void renderSavedHistory() {
        String saved = prefs.getString("chat_history", "");
        if (saved.isEmpty()) return;
        for (String line : saved.split("\n")) {
            if (!line.trim().isEmpty()) addBubble(line);
        }
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** Bubble chat: ucapan lansia ("Anda:") kanan, asisten kiri. */
    private void addBubble(String entry) {
        boolean isUser = entry.contains("] Anda:");
        TextView tv = new TextView(this);
        tv.setText(entry);
        tv.setTextSize(chatTextSize);
        tv.setLineSpacing(0f, 1.15f);
        tv.setPadding(20, 14, 20, 14);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 4, 0, 4);

        if (isUser) {
            lp.gravity = android.view.Gravity.END;
            tv.setBackgroundResource(R.drawable.bg_bubble_user);
            tv.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        } else {
            lp.gravity = android.view.Gravity.START;
            tv.setBackgroundResource(R.drawable.bg_bubble_assistant);
            tv.setTextColor(ContextCompat.getColor(this, R.color.baachan_primary_dark));
        }
        chatContainer.addView(tv, lp);
    }

    /** Haptic ringan saat menekan tombol besar (tanpa mengganggu click listener). */
    private void setupHaptics(View... buttons) {
        for (View b : buttons) {
            if (b == null) continue;
            b.setOnTouchListener((v, ev) -> {
                if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                }
                return false; // false = click listener tetap jalan
            });
        }
    }

    private void updateStatusUI(String status) {
        handler.post(() -> {
            statusText.setText(status);
            // sinyal visual: warna kartu + ikon berubah sesuai state
            String s = status.toLowerCase(Locale.ROOT);
            String icon = "👋";
            int card = ContextCompat.getColor(this, R.color.baachan_primary);
            if (s.contains("mendengarkan")) {
                icon = "🎤";
                card = 0xFF2E7D32; // hijau: sedang dengar
            } else if (s.contains("kepanikan")) {
                icon = "🚨";
                card = 0xFFD32F2F; // merah: darurat
            } else if (s.contains("mengenali") || s.contains("mencari") || s.contains("cek")
                    || s.contains("menghubungi") || s.contains("mengambil")) {
                icon = "🤔";
                card = 0xFFF9A825; // kuning: berpikir/proses
            } else if (s.contains("menjawab") || s.contains("bercerita")) {
                icon = "💬";
                card = 0xFF1565C0; // biru: bicara
            }
            micIcon.setText(icon);
            statusCard.getBackground().mutate().setTint(card);
        });
    }

    private void applyAccessibilitySettings() {
        int colorPrimary, colorEmergency, colorAccent;
        if (highContrastMode) {
            colorPrimary = ContextCompat.getColor(this, R.color.hc_primary);
            colorEmergency = ContextCompat.getColor(this, R.color.hc_emergency);
            colorAccent = ContextCompat.getColor(this, R.color.hc_accent);
        } else {
            colorPrimary = ContextCompat.getColor(this, R.color.baachan_primary);
            colorEmergency = ContextCompat.getColor(this, R.color.baachan_emergency);
            colorAccent = ContextCompat.getColor(this, R.color.baachan_accent);
        }
        if (statusCard != null) {
            statusCard.getBackground().mutate().setTint(colorPrimary);
        }
        emergencyButton.setBackgroundColor(colorEmergency);
        btnHealth.setBackgroundColor(colorPrimary);
        btnWeather.setBackgroundColor(colorAccent);

        switch (fontSizeScale) {
            case FONT_SIZE_LARGE:
                statusText.setTextSize(34);
                chatTextSize = 20f;
                break;
            case FONT_SIZE_XLARGE:
                statusText.setTextSize(40);
                chatTextSize = 24f;
                break;
            default:
                statusText.setTextSize(28);
                chatTextSize = 16f;
                break;
        }
    }

    private void updateAssistantGreeting() {
        int hour = Integer.parseInt(new SimpleDateFormat("HH", Locale.getDefault()).format(new Date()));
        String greeting;
        if (hour >= 4 && hour < 11) greeting = "Selamat pagi " + nenek_name + "!";
        else if (hour >= 11 && hour < 15) greeting = "Selamat siang " + nenek_name + "!";
        else if (hour >= 15 && hour < 18) greeting = "Selamat sore " + nenek_name + "!";
        else greeting = "Selamat malam " + nenek_name + "!";
        assistantGreeting.setText(greeting);
    }

    private void handleHealthButton() {
        org.json.JSONArray times = ReminderReceiver.getMedTimes(prefs);
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
        if (!any) sb.append("belum ada yang aktif. Buka pengaturan untuk atur.");
        else sb.append(". Jangan lupa minum obat ya ").append(nenek_name).append(". Semoga cepat sehat!");

        // catatan tensi darah terakhir + riwayat (kalau fiturnya aktif)
        if (prefs.getBoolean("bp_enabled", false)) {
            BloodPressureStore.Entry bp = BloodPressureStore.latest(this);
            if (bp != null) {
                sb.append(" Tensi darah terakhir dicatat ").append(bp.date)
                  .append(": ").append(bp.sys).append(" atas ").append(bp.dia).append(".");
                if (bp.sys >= 140 || bp.dia >= 90) {
                    sb.append(" Itu agak tinggi, jangan lupa kontrol ke dokter ya.");
                } else {
                    sb.append(" Alhamdulillah masih bagus.");
                }
                if (BloodPressureStore.count(this) > 1) {
                    sb.append(" ").append(BloodPressureStore.speechHistory(this, nenek_name));
                }
            } else {
                sb.append(" Belum ada catatan tensi darah. Kalau sudah diukur, bilang aja, misalnya tensi darahku 130 atas 85.");
            }
        }

        // streak minum obat tepat waktu
        int streak = prefs.getInt("med_streak", 0);
        if (streak >= 3) {
            sb.append(" Kamu udah ").append(streak)
              .append(" hari berturut-turut minum obat tepat waktu, luar biasa ").append(nenek_name).append("!");
        }

        String healthMsg = sb.toString();
        addToHistory(assistant_name + ": " + healthMsg);
        speakResponse(healthMsg, 1.0f, 0.8f);
    }

    private void handleWeatherButton() {
        fetchWeatherWithLocation(false);
    }


    private void checkBatteryLevel() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm != null) {
            lastBatteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (lastBatteryLevel <= 20 && !batteryWarningSpoken) {
                batteryWarningSpoken = true;
                String warning = "Perhatian, baterai HP tinggal " + lastBatteryLevel + " persen. "
                        + "Sebaiknya segera dicharge ya " + nenek_name + ".";
                handler.postDelayed(() -> {
                    addToHistory(assistant_name + ": [Baterai] " + warning);
                    speakResponse(warning, 1.0f, 0.8f);
                }, 2000);
            }
        }
    }

    private boolean checkInternetConnection() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        }
        return false;
    }

    private void sendEmergencySMS() {
        String emergencyNumber = prefs.getString("emergency_number", "");
        if (emergencyNumber.isEmpty() || emergencyNumber.contains("XXXXXXXXXX")) {
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            String locationText = "";
            if (location != null) {
                double lat = location.getLatitude();
                double lon = location.getLongitude();
                locationText = "Lokasi: https://maps.google.com/?q=" + lat + "," + lon;
            }

            String smsMessage = "Darurat! " + nenek_name + " membutuhkan bantuan! "
                    + assistant_name + " mendeteksi situasi darurat. "
                    + locationText;

            try {
                SmsManager smsManager = SmsManager.getDefault();
                smsManager.sendTextMessage(emergencyNumber, null, smsMessage, null, null);
                addToHistory(assistant_name + ": SMS darurat terkirim ke " + emergencyNumber);
            } catch (Exception e) {
                addToHistory(assistant_name + ": Gagal kirim SMS darurat");
            }
        });
    }

    private void cleanTempFile() {
        if (tempAudioFile != null && tempAudioFile.exists()) {
            tempAudioFile.delete();
        }
    }

    private void saveLocationForAdzan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            if (location != null) {
                prefs.edit()
                        .putFloat("last_lat", (float) location.getLatitude())
                        .putFloat("last_lon", (float) location.getLongitude())
                        .apply();
            }
        });
    }

    private void handleFavoriteContact() {
        if (favoriteContactNumber.isEmpty()) {
            speakResponse("Belum ada kontak favorit yang diatur. Buka pengaturan untuk atur.", 0.85f, 0.75f);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Izin telepon tidak diberikan", Toast.LENGTH_SHORT).show();
            return;
        }
        addToHistory(assistant_name + ": Menelpon " + favoriteContactName + "...");
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + favoriteContactNumber));
        startActivity(callIntent);
    }

    private void updateCheckInStatus() {
        handler.post(() -> {
            if (checkInEnabled) {
                String timeStr = String.format("%02d:%02d", checkInHour, checkInMinute);
                checkInStatus.setText("Cek harian: Aktif - " + timeStr);
            } else {
                checkInStatus.setText("Cek harian: Tidak aktif");
            }
        });
    }

    private void callEmergency() {
        String emergencyNumber = prefs.getString("emergency_number", "").trim();
        if (emergencyNumber.isEmpty()) {
            Toast.makeText(this, "Nomor darurat belum diatur di pengaturan", Toast.LENGTH_LONG).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Izin telepon tidak diberikan", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean callGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED;
        if (callGranted) {
            if (!hasActiveSim()) {
                if (!showedSimWarning) {
                    showedSimWarning = true;
                    prefs.edit().putBoolean("sim_warning_shown", true).apply();
                    speakResponse("Perhatian " + nenek_name + ", kartu SIM HP ini belum terpasang atau belum aktif. "
                            + "Panggilan darurat belum bisa jalan. Mohon dicheck dulu ya.", 1.0f, 0.8f);
                }
                return;
            }
            if (!isCallEnabled()) {
                speakResponse("Maaf " + nenek_name + ", panggilan HP ini lagi dimatikan biar aman. "
                        + "Buka menu panggilan darurat untuk mengaktifkan lagi ya.", 0.85f, 0.75f);
                return;
            }
            if (callInProgress) return;
            callInProgress = true;
            handler.postDelayed(() -> callInProgress = false, 20000);
        }
        addToHistory("Melakukan panggilan darurat...");
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + emergencyNumber));
        try {
            startActivity(callIntent);
        } catch (Exception e) {
            callInProgress = false;
        }
    }

    /** Deteksi SIM aktif (CDMA Dual-SIM kadang gagal, jadi fallback aman = true). */
    private boolean hasActiveSim() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return true; // fallback: jangan blokir
            if (tm.getSimState() == android.telephony.TelephonyManager.SIM_STATE_ABSENT) return false;
            String number = tm.getLine1Number();
            return number != null && !number.trim().isEmpty();
        } catch (Exception e) {
            return true; // fallback: jangan blokir darurat karena error deteksi
        }
    }

    /** Master switch panggilan darurat (nambahin lapisan safety, default aktif). */
    private boolean isCallEnabled() {
        return prefs.getBoolean("call_enabled", true);
    }

    private boolean hasAllPermissions(String[] permissions) {
        for (String p : permissions)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            boolean micGranted = hasAllPermissions(new String[]{ Manifest.permission.RECORD_AUDIO });
            if (micGranted) {
                startWhisperListening();
            } else {
                Toast.makeText(this, "Izin mikrofon diperlukan untuk mode suara", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void writeWavFile(File file, byte[] audioData) throws Exception {
        int dataSize = audioData.length;
        int fileSize = dataSize + 36;

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write("RIFF".getBytes());
            fos.write(intToByteArray(fileSize));
            fos.write("WAVE".getBytes());

            fos.write("fmt ".getBytes());
            fos.write(intToByteArray(16));
            fos.write(shortToByteArray((short) 1));
            fos.write(shortToByteArray((short) 1));
            fos.write(intToByteArray(AudioUtil.SAMPLE_RATE));
            fos.write(intToByteArray(AudioUtil.SAMPLE_RATE * 2));
            fos.write(shortToByteArray((short) 2));
            fos.write(shortToByteArray((short) 16));

            fos.write("data".getBytes());
            fos.write(intToByteArray(dataSize));
            fos.write(audioData);
        }
    }

    private byte[] intToByteArray(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    private byte[] shortToByteArray(short value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF)
        };
    }
}
