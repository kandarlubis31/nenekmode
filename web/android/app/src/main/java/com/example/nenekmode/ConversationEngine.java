package com.example.nenekmode;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.List;

/**
 * Otak percakapan lokal: alur konfirmasi minum obat, kuis, doa,
 * mode nostalgia, perintah lagu/berita, gerbang wake word,
 * dan ekstraksi fakta ke memori jangka panjang.
 */
public class ConversationEngine {

    public static final String MUSIC_MARKER = "PUTAR_LAGU:";
    public static final String NEWS_MARKER = "BACA_BERITA";

    private static final String[] WAKE_WORDS = {"kandar", "kandhar", "kandar", "asisten"};

    public interface Output { void speak(String text); }

    private final Context context;
    private final MemoryStore memory;
    private final ContentBank content;
    private final Output out;
    private final SharedPreferences prefs;

    // state kuis
    private String currentQuiz = "";
    private String currentQuizAnswer = "";
    // state nostalgia (false = LLM biasa; true = semua input jadi pengalaman cerita)
    private boolean nostalgiaMode = false;

    public ConversationEngine(Context context, MemoryStore memory, ContentBank content, Output out) {
        this.context = context;
        this.memory = memory;
        this.content = content;
        this.out = out;
        this.prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
    }

    public boolean isNostalgiaMode() { return nostalgiaMode; }
    public void setNostalgiaMode(boolean v) { nostalgiaMode = v; }

    public boolean isWaitingMedConfirm() {
        return prefs.getBoolean("med_confirm_waiting", false);
    }

    public boolean isWaitingQuizAnswer() {
        return prefs.getBoolean("quiz_waiting", false);
    }

    /** Nama asisten (wake word ikut settings). */
    public String getAssistantName() {
        return prefs.getString("assistant_name", "Kandar");
    }

    // ================= GERBANG WAKE WORD =================

    /**
     * Kalau wake gate aktif, input harus mengandung nama asisten
     * (atau sedang dalam alur aktif: kuis / obat / nostalgia) supaya diproses.
     */
    public boolean passesWakeGate(String raw) {
        String wakeMode = prefs.getString("wake_mode", "off");
        if (!"on".equals(wakeMode)) return true;
        if (isWaitingMedConfirm() || isWaitingQuizAnswer() || nostalgiaMode) return true;

        String assistantName = getAssistantName().toLowerCase();
        for (String w : WAKE_WORDS) {
            if (raw.contains(w)) return true;
        }
        return assistantName.isEmpty() || raw.contains(assistantName);
    }

    public void notifyWakeIgnored() {
        String assistantName = getAssistantName();
        out.speak("Panggil dulu " + assistantName + " ya, baru " + assistantName + " jawab.");
    }

    // ================= FOLLOW-UP EMOSIONAL =================

    private static final String[] COMPLAINT_WORDS = {
        "pusing", "sakit", "capek", "lelah", "pegal", "linu", "nyeri", "mual", "muntah",
        "sesak", "demam", "batuk", "flu", "masuk angin", "rematik", "gatel",
        "gak enak badan", "nggak enak badan", "kurang enak badan", "badan gak enak", "badan nggak enak"
    };

    public static String detectComplaint(String raw) {
        if (raw == null) return null;
        for (String w : COMPLAINT_WORDS) {
            if (raw.contains(w)) return w;
        }
        return null;
    }

    private static org.json.JSONObject getPendingFollowup(Context ctx) {
        try {
            String s = ctx.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE)
                    .getString("pending_followup", "");
            if (s.isEmpty()) return null;
            return new org.json.JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean hasPendingFollowup(Context ctx) {
        return getPendingFollowup(ctx) != null;
    }

    public static void storeFollowup(Context ctx, String complaint) {
        if (hasPendingFollowup(ctx)) return; // jangan numpuk
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("complaint", complaint);
            o.put("ts", System.currentTimeMillis());
            ctx.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE)
                    .edit().putString("pending_followup", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void clearFollowup() {
        prefs.edit().remove("pending_followup").apply();
    }

    /**
     * Pertanyaan follow-up, atau null kalau tidak ada / masih terlalu cepat (<30 menit)
     * / sudah terlalu lama (>24 jam, dianggap basi dan dibuang).
     */
    public static String getFollowupQuestion(Context ctx, String elderName) {
        org.json.JSONObject o = getPendingFollowup(ctx);
        if (o == null) return null;
        long age = System.currentTimeMillis() - o.optLong("ts", 0);
        if (age < 30 * 60 * 1000L) return null;
        if (age > 24 * 60 * 60 * 1000L) {
            ctx.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE)
                    .edit().remove("pending_followup").apply();
            return null;
        }
        String c = o.optString("complaint", "kurang sehat");
        return "tadi " + elderName + " cerita " + c + ". Sekarang udah membaik belum?";
    }

    // ================= PERINTAH CEPAT =================

    /**
     * @return true kalau input sudah ditangani lokal (marker atau alur aktif),
     *         false kalau harus diteruskan ke LLM.
     */
    public boolean handleQuickCommands(String raw) {
        // --- konfirmasi minum obat (prioritas tertinggi) + streak ---
        if (isWaitingMedConfirm()) {
            boolean yes = raw.contains("sudah") || raw.contains("udah")
                    || raw.equals("ya") || raw.contains("sudah minum") || raw.contains("udah minum");
            boolean no = raw.contains("belum") || raw.contains("belum minum")
                    || raw.contains("belum kok") || raw.equals("nggak") || raw.contains("belum jadi");
            if (yes || no) {
                prefs.edit().putBoolean("med_confirm_waiting", false).apply();
                if (yes) {
                    String praise = updateMedStreak();
                    out.speak("Alhamdulillah, jangan lupa diminum habis ya " + nenekName() + "." + praise);
                } else {
                    out.speak("Yuk diminum sekarang ya " + nenekName() + ", biar cepat sembuh. Kalau sudah, bilang sudah ya.");
                    ReminderReceiver.snoozeTenMinutes(context);
                }
                return true;
            }
            // input lain saat menunggu konfirmasi -> tetap teruskan ke LLM
            return false;
        }

        // --- follow-up emosional: balasan atas keluhan sebelumnya ---
        if (hasPendingFollowup(context)) {
            boolean better = raw.contains("sudah") || raw.contains("udah") || raw.contains("membaik")
                    || raw.contains("enakan") || raw.contains("sehat") || raw.equals("baik")
                    || raw.contains("sudah baik");
            boolean still = raw.contains("belum") || raw.contains("masih")
                    || raw.contains("gak membaik") || raw.contains("nggak membaik");
            if (better || still) {
                clearFollowup();
                if (better) {
                    out.speak("Syukurlah " + nenekName() + ", semoga cepat sehat sepenuhnya ya. Kalau muncul lagi, kabari aku ya.");
                } else {
                    out.speak("Semoga lekas membaik " + nenekName() + ". Istirahat yang cukup ya, dan kalau berlanjut, suruh keluarga bawa ke dokter ya.");
                }
                return true;
            }
            // jawaban nggak jelas -> biarkan lanjut normal, follow-up tetap tersimpan
        }

        // --- jawaban kuis ---
        if (isWaitingQuizAnswer()) {
            boolean wantsStop = raw.contains("nggak jadi") || raw.contains("stop")
                    || raw.contains("berhenti") || raw.contains("udahan");
            if (wantsStop) {
                prefs.edit().putBoolean("quiz_waiting", false).apply();
                currentQuiz = "";
                out.speak("Oke, kuisnya di-stop dulu. Kalau mau lagi, bilang aja mulai kuis.");
                return true;
            }
            boolean correct = content.checkQuizAnswer(currentQuiz, raw);
            prefs.edit().putBoolean("quiz_waiting", false).apply();
            String answer = currentQuizAnswer;
            currentQuiz = "";
            currentQuizAnswer = "";
            if (correct) {
                out.speak("Wih, benar sekali! Pinter banget " + nenekName() + ". Mau coba lagi nggak? Bilang aja mulai kuis.");
            } else {
                out.speak("Hampir benar! Jawabannya " + answer + ". Makanya ramai-ramai belajar bareng ya. Mau coba lagi? Bilang mulai kuis.");
            }
            return true;
        }

        // --- mode nostalgia: cerita mengalir bebas ke LLM, jangan disadap fitur lain ---
        if (nostalgiaMode) {
            return false;
        }

        // --- perintah mulai kuis ---
        if (raw.contains("kuis") || raw.contains("tebak") || raw.contains("kuis tebak")) {
            currentQuiz = content.getRandomQuizQuestion();
            currentQuizAnswer = content.getQuizAnswer(currentQuiz);
            prefs.edit().putBoolean("quiz_waiting", true).apply();
            out.speak("Oke, kuis ringan dulu " + nenekName() + ". " + currentQuiz);
            return true;
        }

        // --- perintah doa ---
        if (raw.contains("doa") && !raw.contains("jadwal") && !raw.contains("adzan")) {
            String tod = getTimeOfDay();
            out.speak(content.getDoa(tod));
            return true;
        }

        // --- pengingat minum air (konfirmasi) ---
        if (prefs.getBoolean("water_confirm_waiting", false)) {
            boolean drank = raw.contains("sudah") || raw.contains("udah") || raw.equals("ya");
            boolean notYet = raw.contains("belum") || raw.equals("nggak");
            if (drank || notYet) {
                prefs.edit().putBoolean("water_confirm_waiting", false).apply();
                if (drank) {
                    out.speak("Pintar! Air putih itu obat yang paling murah " + nenekName() + ".");
                } else {
                    out.speak("Yuk diminum sekarang ya " + nenekName() + ", sekalian abis jalan dikit ke dapur.");
                }
                return true;
            }
        }

        // --- tensi darah ---
        if (prefs.getBoolean("bp_enabled", false)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?:tekanan darah|tensi)\\s*(?:ku|saya|aku)?\\s*(\\d{2,3})\\s*(?:atas|per|/)\\s*(\\d{2,3})").matcher(raw);
            if (m.find()) {
                int sys = Integer.parseInt(m.group(1));
                int dia = Integer.parseInt(m.group(2));
                if (BloodPressureStore.add(context, sys, dia)) {
                    out.speak(BloodPressureStore.speechAfterAdd(context, nenekName(), sys, dia));
                } else {
                    out.speak("Angkanya kayaknya kurang pas " + nenekName() + ". Coba sebut lagi, misalnya 130 atas 85.");
                }
                return true;
            }
            if ((raw.contains("tekanan darah") || raw.contains("tensi"))
                    && (raw.contains("berapa") || raw.contains("catatan") || raw.contains("terakhir"))) {
                out.speak(BloodPressureStore.speechLast(context, nenekName()));
                return true;
            }
        }

        // --- cerita & nasihat ---
        if (prefs.getBoolean("stories_enabled", false)
                && (raw.contains("ceritain") || raw.contains("dongeng")
                || raw.contains("cerita dong") || raw.contains("nasihat"))) {
            out.speak(content.getRandomStory());
            return true;
        }

        // --- adzan / jadwal sholat ---
        boolean adzanWord = raw.contains("adzan") || raw.contains("azan");
        boolean jadwalSholat = raw.contains("jadwal sholat") || raw.contains("waktu sholat")
                || (raw.contains("sholat") && raw.contains("jam"));
        boolean prayerNameQuery = false;
        String[] prayerNames = {"subuh", "dzuhur", "ashar", "maghrib", "isya", "asar", "lohor"};
        for (String p : prayerNames) {
            if (raw.contains(p) && raw.contains("jam")) { prayerNameQuery = true; break; }
        }
        if (adzanWord || jadwalSholat || prayerNameQuery) {
            if (raw.contains("jadwal") || raw.contains("semua")) {
                out.speak(PrayerTimes.scheduleSpeech(context, nenekName()));
            } else {
                out.speak(PrayerTimes.nextPrayerSpeech(context, nenekName()));
            }
            return true;
        }

        // --- mode nostalgia ---
        if (raw.contains("cerita dulu") || raw.contains("masa lalu")
                || raw.contains("waktu muda") || raw.contains("zaman dulu")
                || raw.contains("jaman dulu") || raw.contains("kenangan")) {
            nostalgiaMode = true;
            String opener = content.getRandomNostalgiaOpener(nenekName());
            out.speak(opener);
            return true;
        }

        // --- keluar mode nostalgia ---
        if (nostalgiaMode && (raw.contains("udahan") || raw.contains("sudah dulu")
                || raw.contains("capek cerita") || raw.contains("nanti lagi")
                || raw.contains("berhenti cerita"))) {
            nostalgiaMode = false;
            out.speak("Terima kasih sudah cerita " + nenekName() + ", aku senang sekali dengarnya. Sampai jumpa lagi ya.");
            return true;
        }

        // --- lagu ---
        if (raw.contains("lagu") || raw.contains("musik") || raw.contains("dengerin lagu")
                || raw.contains("putarin") || raw.contains("putar lagu")) {
            out.speak(MUSIC_MARKER + raw);
            return true;
        }

        // --- berita ---
        if (raw.contains("berita") || raw.contains("kabar hari ini") || raw.contains("kabarku")
                || raw.contains("headline") || raw.contains("baca berita")) {
            out.speak(NEWS_MARKER);
            return true;
        }

        // --- lupa memori ---
        if (raw.contains("hapus ingatan") || raw.contains("hapus memori")
                || raw.contains("lupain semua") || raw.contains("hapus semua ingatan")) {
            memory.clear();
            out.speak("Semua ingatanku tentang " + nenekName() + " sudah aku hapus. Kita mulai baru lagi ya.");
            return true;
        }

        return false;
    }

    /**
     * Dipanggil setiap jawaban LLM selesai; mengekstrak fakta jangka panjang
     * dan menutup mode nostalgia kalau user pamit.
     */
    public void onAssistantResponse(String userText, String assistantText) {
        List<String> facts = MemoryStore.extractFacts(userText);
        for (String f : facts) memory.add(f);

        // deteksi keluhan -> simpan buat follow-up nanti
        String complaint = detectComplaint(userText);
        if (complaint != null) storeFollowup(context, complaint);
    }

    /**
     * Streak minum obat tepat waktu: hari ini != kemarin -> +1, else reset ke 1.
     * @return kalimat pujian ("" jika belum layak)
     */
    private String updateMedStreak() {
        try {
            java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            String today = df.format(cal.getTime());
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
            String yesterday = df.format(cal.getTime());

            String last = prefs.getString("med_streak_date", "");
            int streak = prefs.getInt("med_streak", 0);
            if (today.equals(last)) return "";
            streak = yesterday.equals(last) ? streak + 1 : 1;
            prefs.edit().putInt("med_streak", streak).putString("med_streak_date", today).apply();
            if (streak >= 3) {
                return " Itu hari ke-" + streak + " berturut-turut minum obat tepat waktu, hebat banget " + nenekName() + "!";
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ================= HELPERS =================

    private String nenekName() {
        return prefs.getString("nenek_name", "Bu");
    }

    /** Mode malam: jam tidur dari settings, mis. 21:00-05:00. */
    public static boolean isNightMode(Context context) {
        SharedPreferences p = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        if (!p.getBoolean("night_mode_enabled", false)) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int start = p.getInt("night_start_hour", 21);
        int end = p.getInt("night_end_hour", 5);
        return start > end ? (h >= start || h < end) : (h >= start && h < end);
    }

    public static String getTimeOfDay() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 4 && h < 11) return "pagi";
        if (h >= 11 && h < 15) return "siang";
        if (h >= 15 && h < 19) return "sore";
        return "malam";
    }
}
