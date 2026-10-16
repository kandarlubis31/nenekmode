/**
 * Otak percakapan lokal: alur konfirmasi minum obat, kuis, doa,
 * mode nostalgia, perintah lagu/berita, gerbang wake word,
 * dan ekstraksi fakta ke memori jangka panjang.
 *
 * Port setia ConversationEngine.java. Efek samping Android (alarm, adzan,
 * TTS) dipompa lewat interface EngineEffects agar bisa dipasang plugin native.
 */

import { prefs } from "./prefs";
import { MemoryStore } from "./memory";
import { content } from "./content";
import { bp } from "./bp";

export const MUSIC_MARKER = "PUTAR_LAGU:";
export const NEWS_MARKER = "BACA_BERITA";

const WAKE_WORDS = ["kandar", "kandhar", "kandar", "asisten"];

const COMPLAINT_WORDS = [
  "pusing", "sakit", "capek", "lelah", "pegal", "linu", "nyeri", "mual", "muntah",
  "sesak", "demam", "batuk", "flu", "masuk angin", "rematik", "gatel",
  "gak enak badan", "nggak enak badan", "kurang enak badan",
  "badan gak enak", "badan nggak enak",
];

export interface EngineOutput {
  speak(text: string): void;
}

/** Efek samping yang butuh native/async di web — dipasang dari luar. */
export interface EngineEffects {
  /** ReminderReceiver.snoozeTenMinutes: snooze alarm obat 10 menit. */
  snoozeMedTenMinutes(): void;
  /** Kalimat jadwal sholat lengkap (PrayerTimes.scheduleSpeech). */
  prayerScheduleSpeech(elderName: string): string;
  /** Kalimat sholat berikutnya (PrayerTimes.nextPrayerSpeech). */
  prayerNextSpeech(elderName: string): string;
}

interface PendingFollowup {
  complaint: string;
  ts: number;
}

function getPendingFollowup(): PendingFollowup | null {
  const s = prefs.getString("pending_followup", "");
  if (!s) return null;
  try {
    return JSON.parse(s) as PendingFollowup;
  } catch {
    return null;
  }
}

export function hasPendingFollowup(): boolean {
  return getPendingFollowup() !== null;
}

export function storeFollowup(complaint: string): void {
  if (hasPendingFollowup()) return; // jangan numpuk
  prefs.setString(
    "pending_followup",
    JSON.stringify({ complaint, ts: Date.now() }),
  );
}

/**
 * Pertanyaan follow-up, atau null kalau tidak ada / masih terlalu cepat (<30 menit)
 * / sudah terlalu lama (>24 jam, dianggap basi dan dibuang).
 */
export function getFollowupQuestion(elderName: string): string | null {
  const o = getPendingFollowup();
  if (!o) return null;
  const age = Date.now() - (o.ts ?? 0);
  if (age < 30 * 60 * 1000) return null;
  if (age > 24 * 60 * 60 * 1000) {
    prefs.remove("pending_followup");
    return null;
  }
  const c = o.complaint || "kurang sehat";
  return `tadi ${elderName} cerita ${c}. Sekarang udah membaik belum?`;
}

export class ConversationEngine {
  private readonly memory: MemoryStore;
  private readonly out: EngineOutput;
  private readonly fx: EngineEffects;

  // state kuis
  private currentQuiz = "";
  private currentQuizAnswer = "";
  // state nostalgia (false = LLM biasa)
  private nostalgiaMode = false;

  constructor(memory: MemoryStore, out: EngineOutput, fx: EngineEffects) {
    this.memory = memory;
    this.out = out;
    this.fx = fx;
  }

  isNostalgiaMode(): boolean {
    return this.nostalgiaMode;
  }

  isWaitingMedConfirm(): boolean {
    return prefs.getBoolean("med_confirm_waiting", false);
  }

  isWaitingQuizAnswer(): boolean {
    return prefs.getBoolean("quiz_waiting", false);
  }

  /** Nama asisten (wake word ikut settings). */
  getAssistantName(): string {
    return prefs.getString("assistant_name", "Kandar");
  }

  // ================= GERBANG WAKE WORD =================

  /**
   * Kalau wake gate aktif, input harus mengandung nama asisten
   * (atau sedang dalam alur aktif: kuis / obat / nostalgia) supaya diproses.
   */
  passesWakeGate(raw: string): boolean {
    const wakeMode = prefs.getString("wake_mode", "off");
    if (wakeMode !== "on") return true;
    if (this.isWaitingMedConfirm() || this.isWaitingQuizAnswer() || this.nostalgiaMode) {
      return true;
    }
    const assistantName = this.getAssistantName().toLowerCase();
    const lower = raw.toLowerCase();
    for (const w of WAKE_WORDS) {
      if (lower.includes(w)) return true;
    }
    return assistantName.length === 0 || lower.includes(assistantName);
  }

  notifyWakeIgnored(): void {
    const assistantName = this.getAssistantName();
    this.out.speak(`Panggil dulu ${assistantName} ya, baru ${assistantName} jawab.`);
  }

  // ================= FOLLOW-UP EMOSIONAL =================

  static detectComplaint(raw: string | null | undefined): string | null {
    if (!raw) return null;
    const lower = raw.toLowerCase();
    for (const w of COMPLAINT_WORDS) {
      if (lower.includes(w)) return w;
    }
    return null;
  }

  clearFollowup(): void {
    prefs.remove("pending_followup");
  }

  // ================= PERINTAH CEPAT =================

  /**
   * @returns true kalau input sudah ditangani lokal (marker atau alur aktif),
   *          false kalau harus diteruskan ke LLM.
   */
  handleQuickCommands(rawInput: string): boolean {
    // pencocokan di Java memakai contains pada teks apa adanya; variasi
    // kapitalisasi dibakukan dulu biar "Ya"/"Sudah" tetap kebaca.
    const raw = rawInput.toLowerCase();
    const nenekName = () => prefs.getString("nenek_name", "Bu");

    // --- konfirmasi minum obat (prioritas tertinggi) + streak ---
    if (this.isWaitingMedConfirm()) {
      const yes =
        raw.includes("sudah") || raw.includes("udah") ||
        raw === "ya" || raw.includes("sudah minum") || raw.includes("udah minum");
      const no =
        raw.includes("belum") || raw.includes("belum minum") ||
        raw.includes("belum kok") || raw === "nggak" || raw.includes("belum jadi");
      if (yes || no) {
        prefs.setBoolean("med_confirm_waiting", false);
        if (yes) {
          const praise = this.updateMedStreak();
          this.out.speak(
            `Alhamdulillah, jangan lupa diminum habis ya ${nenekName()}.` + praise,
          );
        } else {
          this.out.speak(
            `Yuk diminum sekarang ya ${nenekName()}, biar cepat sembuh. Kalau sudah, bilang sudah ya.`,
          );
          this.fx.snoozeMedTenMinutes();
        }
        return true;
      }
      // input lain saat menunggu konfirmasi -> tetap teruskan ke LLM
      return false;
    }

    // --- follow-up emosional: balasan atas keluhan sebelumnya ---
    if (hasPendingFollowup()) {
      const better =
        raw.includes("sudah") || raw.includes("udah") || raw.includes("membaik") ||
        raw.includes("enakan") || raw.includes("sehat") || raw === "baik" ||
        raw.includes("sudah baik");
      const still =
        raw.includes("belum") || raw.includes("masih") ||
        raw.includes("gak membaik") || raw.includes("nggak membaik");
      if (better || still) {
        this.clearFollowup();
        if (better) {
          this.out.speak(
            `Syukurlah ${nenekName()}, semoga cepat sehat sepenuhnya ya. Kalau muncul lagi, kabari aku ya.`,
          );
        } else {
          this.out.speak(
            `Semoga lekas membaik ${nenekName()}. Istirahat yang cukup ya, dan kalau berlanjut, suruh keluarga bawa ke dokter ya.`,
          );
        }
        return true;
      }
      // jawaban nggak jelas -> biarkan lanjut normal, follow-up tetap tersimpan
    }

    // --- jawaban kuis ---
    if (this.isWaitingQuizAnswer()) {
      const wantsStop =
        raw.includes("nggak jadi") || raw.includes("stop") ||
        raw.includes("berhenti") || raw.includes("udahan");
      if (wantsStop) {
        prefs.setBoolean("quiz_waiting", false);
        this.currentQuiz = "";
        this.out.speak("Oke, kuisnya di-stop dulu. Kalau mau lagi, bilang aja mulai kuis.");
        return true;
      }
      const correct = content.checkQuizAnswer(this.currentQuiz, raw);
      prefs.setBoolean("quiz_waiting", false);
      const answer = this.currentQuizAnswer;
      this.currentQuiz = "";
      this.currentQuizAnswer = "";
      if (correct) {
        this.out.speak(
          `Wih, benar sekali! Pinter banget ${nenekName()}. Mau coba lagi nggak? Bilang aja mulai kuis.`,
        );
      } else {
        this.out.speak(
          `Hampir benar! Jawabannya ${answer}. Makanya ramai-ramai belajar bareng ya. Mau coba lagi? Bilang mulai kuis.`,
        );
      }
      return true;
    }

    // --- mode nostalgia: cerita mengalir bebas ke LLM, jangan disadap fitur lain ---
    if (this.nostalgiaMode) {
      return false;
    }

    // --- perintah mulai kuis ---
    if (raw.includes("kuis") || raw.includes("tebak") || raw.includes("kuis tebak")) {
      this.currentQuiz = content.getRandomQuizQuestion();
      this.currentQuizAnswer = content.getQuizAnswer(this.currentQuiz);
      prefs.setBoolean("quiz_waiting", true);
      this.out.speak(`Oke, kuis ringan dulu ${nenekName()}. ${this.currentQuiz}`);
      return true;
    }

    // --- perintah doa ---
    if (raw.includes("doa") && !raw.includes("jadwal") && !raw.includes("adzan")) {
      this.out.speak(content.getDoa(ConversationEngine.getTimeOfDay()));
      return true;
    }

    // --- pengingat minum air (konfirmasi) ---
    if (prefs.getBoolean("water_confirm_waiting", false)) {
      const drank = raw.includes("sudah") || raw.includes("udah") || raw === "ya";
      const notYet = raw.includes("belum") || raw === "nggak";
      if (drank || notYet) {
        prefs.setBoolean("water_confirm_waiting", false);
        if (drank) {
          this.out.speak(`Pintar! Air putih itu obat yang paling murah ${nenekName()}.`);
        } else {
          this.out.speak(
            `Yuk diminum sekarang ya ${nenekName()}, sekalian abis jalan dikit ke dapur.`,
          );
        }
        return true;
      }
    }

    // --- tensi darah ---
    if (prefs.getBoolean("bp_enabled", false)) {
      const m =
        /(?:tekanan darah|tensi)\s*(?:ku|saya|aku)?\s*(\d{2,3})\s*(?:atas|per|\/)\s*(\d{2,3})/.exec(raw);
      if (m) {
        const sys = parseInt(m[1], 10);
        const dia = parseInt(m[2], 10);
        if (bp.add(sys, dia)) {
          this.out.speak(bp.speechAfterAdd(nenekName(), sys, dia));
        } else {
          this.out.speak(
            `Angkanya kayaknya kurang pas ${nenekName()}. Coba sebut lagi, misalnya 130 atas 85.`,
          );
        }
        return true;
      }
      if (
        (raw.includes("tekanan darah") || raw.includes("tensi")) &&
        (raw.includes("berapa") || raw.includes("catatan") || raw.includes("terakhir"))
      ) {
        this.out.speak(bp.speechLast(nenekName()));
        return true;
      }
    }

    // --- cerita & nasihat ---
    if (
      prefs.getBoolean("stories_enabled", false) &&
      (raw.includes("ceritain") || raw.includes("dongeng") ||
        raw.includes("cerita dong") || raw.includes("nasihat"))
    ) {
      this.out.speak(content.getRandomStory());
      return true;
    }

    // --- adzan / jadwal sholat ---
    const adzanWord = raw.includes("adzan") || raw.includes("azan");
    const jadwalSholat =
      raw.includes("jadwal sholat") || raw.includes("waktu sholat") ||
      (raw.includes("sholat") && raw.includes("jam"));
    let prayerNameQuery = false;
    const prayerNames = ["subuh", "dzuhur", "ashar", "maghrib", "isya", "asar", "lohor"];
    for (const p of prayerNames) {
      if (raw.includes(p) && raw.includes("jam")) {
        prayerNameQuery = true;
        break;
      }
    }
    if (adzanWord || jadwalSholat || prayerNameQuery) {
      if (raw.includes("jadwal") || raw.includes("semua")) {
        this.out.speak(this.fx.prayerScheduleSpeech(nenekName()));
      } else {
        this.out.speak(this.fx.prayerNextSpeech(nenekName()));
      }
      return true;
    }

    // --- mode nostalgia: masuk ---
    if (
      raw.includes("cerita dulu") || raw.includes("masa lalu") ||
      raw.includes("waktu muda") || raw.includes("zaman dulu") ||
      raw.includes("jaman dulu") || raw.includes("kenangan")
    ) {
      this.nostalgiaMode = true;
      this.out.speak(content.getRandomNostalgiaOpener(nenekName()));
      return true;
    }

    // --- mode nostalgia: keluar ---
    if (
      this.nostalgiaMode &&
      (raw.includes("udahan") || raw.includes("sudah dulu") ||
        raw.includes("capek cerita") || raw.includes("nanti lagi") ||
        raw.includes("berhenti cerita"))
    ) {
      this.nostalgiaMode = false;
      this.out.speak(
        `Terima kasih sudah cerita ${nenekName()}, aku senang sekali dengarnya. Sampai jumpa lagi ya.`,
      );
      return true;
    }

    // --- lagu ---
    if (
      raw.includes("lagu") || raw.includes("musik") || raw.includes("dengerin lagu") ||
      raw.includes("putarin") || raw.includes("putar lagu")
    ) {
      this.out.speak(MUSIC_MARKER + raw);
      return true;
    }

    // --- berita ---
    if (
      raw.includes("berita") || raw.includes("kabar hari ini") || raw.includes("kabarku") ||
      raw.includes("headline") || raw.includes("baca berita")
    ) {
      this.out.speak(NEWS_MARKER);
      return true;
    }

    // --- lupa memori ---
    if (
      raw.includes("hapus ingatan") || raw.includes("hapus memori") ||
      raw.includes("lupain semua") || raw.includes("hapus semua ingatan")
    ) {
      this.memory.clear();
      this.out.speak(
        `Semua ingatanku tentang ${nenekName()} sudah aku hapus. Kita mulai baru lagi ya.`,
      );
      return true;
    }

    return false;
  }

  /**
   * Dipanggil setiap jawaban LLM selesai; mengekstrak fakta jangka panjang
   * dan menyimpan keluhan buat follow-up nanti.
   */
  onAssistantResponse(userText: string, assistantText: string): void {
    void assistantText;
    const facts = MemoryStore.extractFacts(userText);
    for (const f of facts) this.memory.add(f);

    const complaint = ConversationEngine.detectComplaint(userText);
    if (complaint !== null) storeFollowup(complaint);
  }

  /**
   * Streak minum obat tepat waktu: hari ini != kemarin -> +1, else reset ke 1.
   * @returns kalimat pujian ("" jika belum layak)
   */
  private updateMedStreak(): string {
    try {
      const fmt = (d: Date) =>
        `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
      const now = new Date();
      const today = fmt(now);
      const y = new Date(now.getTime() - 24 * 60 * 60 * 1000);
      const yesterday = fmt(y);

      const last = prefs.getString("med_streak_date", "");
      let streak = prefs.getInt("med_streak", 0);
      if (today === last) return "";
      streak = yesterday === last ? streak + 1 : 1;
      prefs.setInt("med_streak", streak);
      prefs.setString("med_streak_date", today);
      if (streak >= 3) {
        return ` Itu hari ke-${streak} berturut-turut minum obat tepat waktu, hebat banget ${this.nenekName()}!`;
      }
    } catch {
      /* abaikan */
    }
    return "";
  }

  // ================= HELPERS =================

  private nenekName(): string {
    return prefs.getString("nenek_name", "Bu");
  }

  /** Mode malam: jam tidur dari settings, mis. 21:00-05:00. */
  static isNightMode(): boolean {
    if (!prefs.getBoolean("night_mode_enabled", false)) return false;
    const h = new Date().getHours();
    const start = prefs.getInt("night_start_hour", 21);
    const end = prefs.getInt("night_end_hour", 5);
    return start > end ? h >= start || h < end : h >= start && h < end;
  }

  static getTimeOfDay(): string {
    const h = new Date().getHours();
    if (h >= 4 && h < 11) return "pagi";
    if (h >= 11 && h < 15) return "siang";
    if (h >= 15 && h < 19) return "sore";
    return "malam";
  }
}
