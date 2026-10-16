/**
 * Orkestrator asisten: alur percakapan identik MainActivity +
 * CompanionService (proses input -> quick commands -> intent lokal -> LLM,
 * output -> marker/tts), termasuk voice loop VAD -> Whisper.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { prefs } from "../lib/prefs";
import { MemoryStore } from "../lib/memory";
import { IntentMatcher } from "../lib/intents";
import {
  ConversationEngine,
  MUSIC_MARKER,
  NEWS_MARKER,
  getFollowupQuestion,
} from "../lib/conversation";
import {
  getResponse,
  transcribeAudio,
} from "../lib/groq";
import { greeting } from "../lib/greetings";
import * as prayer from "../lib/prayer";
import { news } from "../lib/news";
import { weather } from "../lib/weather";
import {
  HELP_TEXT,
  cleanMusicQuery,
  getGreetingText,
  getDateText,
  getHealthAnnouncement,
  getTimeText,
} from "../lib/actions";
import * as bridge from "../native/bridge";
import { recordWithVad, encodeWav, SAMPLE_RATE } from "../lib/recorder";
import { looksLikeEcho, normalizeSpeech } from "../lib/echoGuard";
import { cancelEscalation } from "../lib/escalation";
import { handleAlarmEvent, type ReceiverUI } from "../lib/receivers";

export type StatusKind = "idle" | "listening" | "thinking" | "speaking" | "panic" | "info";

export interface ChatLine {
  raw: string; // "[14:30] Anda: halo" — format persis seperti native
  isUser: boolean;
}

const HISTORY_KEY = "chat_history";

function nowTs(): string {
  const d = new Date();
  return `${d.getHours() < 10 ? "0" : ""}${d.getHours()}:${d.getMinutes() < 10 ? "0" : ""}${d.getMinutes()}`;
}

function hasInternet(): boolean {
  return navigator.onLine;
}

export function useAssistant() {
  const [statusText, setStatusText] = useState("Menyiapkan...");
  const [chatLines, setChatLines] = useState<ChatLine[]>([]);
  const [listening, setListening] = useState(false);
  const [companionOn, setCompanionOn] = useState(
    prefs.getBoolean("keep_alive", false),
  );
  const [lastSpokenText, setLastSpokenText] = useState("");

  const statusKind: StatusKind = (() => {
    const s = statusText.toLowerCase();
    if (s.includes("kepanikan")) return "panic";
    if (s.includes("mendengarkan")) return "listening";
    if (
      s.includes("mengenali") || s.includes("mencari") || s.includes("cek") ||
      s.includes("menghubungi") || s.includes("mengambil") || s.includes("menyiapkan") ||
      s.includes("berita")
    ) return "thinking";
    if (s.includes("menjawab") || s.includes("bercerita")) return "speaking";
    return "info";
  })();

  const memoryRef = useRef(new MemoryStore());
  const matcherRef = useRef<IntentMatcher | null>(null);
  const engineRef = useRef<ConversationEngine | null>(null);
  const lastSpokenRef = useRef("");
  const greetedRef = useRef(false);
  const batteryWarnedRef = useRef(false);
  const lastBatteryRef = useRef(100);
  const callCooldownRef = useRef(0);
  const simWarnedRef = useRef(false);
  const autoListenRef = useRef(true); // auto loop mic setelah TTS selesai
  const listeningRef = useRef(false);
  const stopFlagRef = useRef(false);
  const lastInputRef = useRef<{ raw: string; at: number }>({ raw: "", at: 0 });
  // transkrip mic terakhir — untuk jeda 30 dtk antar-ucapan identik dari mic
  // (Whisper mengulang hasil palsu utk audio nyaris senyap / gema beruntun)
  const lastMicInputRef = useRef<{ raw: string; at: number }>({ raw: "", at: 0 });

  if (!matcherRef.current) matcherRef.current = new IntentMatcher();

  const updateStatus = useCallback((s: string) => setStatusText(s), []);

  const loadSavedHistory = useCallback(() => {
    const saved = prefs.getString(HISTORY_KEY, "");
    if (!saved) return;
    const seen = new Set<string>();
    const lines: { raw: string; isUser: boolean }[] = [];
    for (const raw of saved.split("\n")) {
      if (!raw.trim()) continue;
      // riwayat lama bisa sudah ter-dobel (bug spam lama) — buang duplikat
      // persis yang berurutan, lalu tulis balik kalau ada yang dibersihkan
      if (seen.has(raw)) continue;
      seen.add(raw);
      lines.push({ raw, isUser: raw.includes("] Anda:") });
    }
    if (lines.length !== saved.split("\n").filter((l) => l.trim()).length) {
      prefs.setString(HISTORY_KEY, lines.map((l) => l.raw).join("\n"));
    }
    setChatLines(lines);
  }, []);

  const appendHistoryLog = useCallback((entry: string) => {
    const prev = prefs.getString(HISTORY_KEY, "");
    prefs.setString(HISTORY_KEY, prev ? prev + "\n" + entry : entry);
  }, []);

  const addToHistory = useCallback(
    (message: string) => {
      const entry = `[${nowTs()}] ${message}`;
      setChatLines((prev) => [...prev, { raw: entry, isUser: entry.includes("] Anda:") }]);
      appendHistoryLog(entry);
    },
    [appendHistoryLog],
  );

  // ================= TTS =================

  // dedupe: kalau ada beberapa speak beruntun (mis. sapaan + peringatan
  // baterai), cukup SATU timer listen terakhir — cegah dua loop mic paralel.
  const listenTimerRef = useRef<number | null>(null);
  const scheduleListening = useCallback((delayMs: number) => {
    if (listenTimerRef.current !== null) window.clearTimeout(listenTimerRef.current);
    listenTimerRef.current = window.setTimeout(() => {
      listenTimerRef.current = null;
      if (autoListenRef.current && !listeningRef.current) {
        void startListeningLoop();
      }
    }, delayMs);
  }, []);

  const speakResponse = useCallback(
    (text: string, pitch = 0.85, rate = 0.75, afterDone?: () => void) => {
      updateStatus("Menjawab...");
      lastSpokenRef.current = text;
      setLastSpokenText(text);
      const night = ConversationEngine.isNightMode();
      void bridge
        .speak({ text, pitch, rate, volume: night ? 0.3 : 1 })
        .then(() => {
          updateStatus("Siap");
          afterDone?.();
          if (greetedRef.current) scheduleListening(300);
        });
    },
    [scheduleListening, updateStatus],
  );

  // ================= OUTPUT ENGINE (marker) =================

  const announceNews = useCallback(() => {
    updateStatus("Ambil berita...");
    speakResponse(
      `Sebentar ya ${prefs.getString("nenek_name", "Bu")}, aku ambil beritanya dulu.`,
      0.85, 0.75,
      () => undefined,
    );
    news.getTopHeadlinesCb({
      onSuccess: (headlines) => {
        const nenek = prefs.getString("nenek_name", "Bu");
        let sb = `Ini berita hari ini ${nenek}. `;
        const n = Math.min(3, headlines.length);
        for (let i = 0; i < n; i++) sb += `Berita ${i + 1}: ${headlines[i]}. `;
        sb += `Gimana ${nenek}, ada yang mau diceritain lebih?`;
        addToHistory(`${prefs.getString("assistant_name", "Kandar")}: [Berita]`);
        speakResponse(sb, 0.85, 0.75);
      },
      onError: () => {
        speakResponse(
          `Maaf ${prefs.getString("nenek_name", "Bu")}, beritanya lagi gak bisa diambil. Coba lagi nanti ya.`,
        );
      },
    });
  }, [addToHistory, speakResponse, updateStatus]);

  const processLocalResponse = useCallback(
    (response: string) => {
      if (!response || !response.trim()) return;
      addToHistory(`${prefs.getString("assistant_name", "Kandar")}: ${response}`);
      speakResponse(response, 0.85, 0.75);
    },
    [addToHistory, speakResponse],
  );

  const handleEngineOutput = useCallback(
    (text: string) => {
      if (!text || !text.trim()) return;
      if (text.startsWith(MUSIC_MARKER)) {
        const query = text.substring(MUSIC_MARKER.length).trim();
        const clean = cleanMusicQuery(query);
        const url = "https://www.youtube.com/results?search_query=" + encodeURIComponent(clean);
        try {
          window.open(url, "_blank");
          processLocalResponse(
            `Oke ${prefs.getString("nenek_name", "Bu")}, lagunya aku carikan di YouTube ya. Mau dengerin yang mana pilih aja.`,
          );
        } catch {
          processLocalResponse(
            `Maaf ${prefs.getString("nenek_name", "Bu")}, gagal buka YouTube. Coba lagi ya.`,
          );
        }
        return;
      }
      if (text.trim() === NEWS_MARKER) {
        announceNews();
        return;
      }
      processLocalResponse(text);
    },
    [announceNews, processLocalResponse],
  );

  if (!engineRef.current) {
    engineRef.current = new ConversationEngine(memoryRef.current, {
      speak: handleEngineOutput,
    }, {
      snoozeMedTenMinutes: () => {
        void bridge.scheduleAlarm({
          code: 500,
          epochMs: Date.now() + 10 * 60 * 1000,
          kind: "reminder_snooze",
          index: 0,
        });
      },
      prayerScheduleSpeech: (elderName) => prayer.scheduleSpeech(elderName),
      prayerNextSpeech: (elderName) => prayer.nextPrayerSpeech(elderName),
    });
  }
  // sinkronkan output engine tiap render (handleEngineOutput stateful)
  engineRef.current = engineRef.current as ConversationEngine;

  // ================= AKSI LOKAL =================

  const fetchWeatherWithLocation = useCallback(
    async (isForecast: boolean) => {
      updateStatus("Mencari lokasi...");
      const loc = await bridge.getLastLocation();
      let lat = -6.2838;
      let lon = 106.8049;
      if (loc) {
        lat = loc.lat;
        lon = loc.lon;
        prefs.setNumber("last_lat", lat);
        prefs.setNumber("last_lon", lon);
      } else {
        updateStatus("GPS gagal, pakai lokasi default...");
      }
      updateStatus("Cek cuaca...");
      try {
        const text = isForecast
          ? await weather.getForecast(lat, lon)
          : await weather.getCurrentWeather(lat, lon);
        processLocalResponse(text);
      } catch (e) {
        processLocalResponse((e as Error).message);
      }
    },
    [processLocalResponse, updateStatus],
  );

  const handleFavoriteContact = useCallback(async () => {
    const number = prefs.getString("favorite_contact_number", "");
    const name = prefs.getString("favorite_contact_name", "");
    if (!number) {
      speakResponse("Belum ada kontak favorit yang diatur. Buka pengaturan untuk atur.");
      return;
    }
    addToHistory(`${prefs.getString("assistant_name", "Kandar")}: Menelpon ${name}...`);
    await bridge.callNumber(number);
  }, [addToHistory, speakResponse]);

  const sendEmergencySMS = useCallback(async () => {
    const number = prefs.getString("emergency_number", "");
    if (!number || number.includes("XXXXXXXXXX")) return;
    const loc = await bridge.getLastLocation();
    const locationText = loc
      ? `Lokasi: https://maps.google.com/?q=${loc.lat},${loc.lon}`
      : "";
    const message =
      `Darurat! ${prefs.getString("nenek_name", "Bu")} membutuhkan bantuan! ` +
      `${prefs.getString("assistant_name", "Kandar")} mendeteksi situasi darurat. ${locationText}`;
    const ok = await bridge.sendSms(number, message);
    addToHistory(
      ok
        ? `${prefs.getString("assistant_name", "Kandar")}: SMS darurat terkirim ke ${number}`
        : `${prefs.getString("assistant_name", "Kandar")}: Gagal kirim SMS darurat`,
    );
  }, [addToHistory]);

  const callEmergency = useCallback(async () => {
    const number = prefs.getString("emergency_number", "").trim();
    if (!number) return;
    if (!(await bridge.hasActiveSim())) {
      if (!simWarnedRef.current) {
        simWarnedRef.current = true;
        prefs.setBoolean("sim_warning_shown", true);
        speakResponse(
          `Perhatian ${prefs.getString("nenek_name", "Bu")}, kartu SIM HP ini belum terpasang atau belum aktif. Panggilan darurat belum bisa jalan. Mohon dicheck dulu ya.`,
          1.0, 0.8,
        );
      }
      return;
    }
    if (!prefs.getBoolean("call_enabled", true)) {
      speakResponse(
        `Maaf ${prefs.getString("nenek_name", "Bu")}, panggilan HP ini lagi dimatikan biar aman. Buka menu panggilan darurat untuk mengaktifkan lagi ya.`,
      );
      return;
    }
    if (Date.now() < callCooldownRef.current) return;
    callCooldownRef.current = Date.now() + 20000;
    addToHistory("Melakukan panggilan darurat...");
    await bridge.callNumber(number);
  }, [addToHistory, speakResponse]);

  const handlePanicSituation = useCallback(() => {
    addToHistory("Sistem mendeteksi situasi darurat!");
    updateStatus("MENDETEKSI KEPANIKAN!");
    try {
      navigator.vibrate?.(2000);
    } catch {
      /* abaikan */
    }
    const panicMessage =
      `${prefs.getString("nenek_name", "Bu")} kenapa? Jangan khawatir, ` +
      `${prefs.getString("assistant_name", "Kandar")} ada kok. ` +
      `${prefs.getString("assistant_name", "Kandar")} akan hubungi yang lain sekarang juga.`;
    speakResponse(panicMessage, 1.15, 0.85);
    const emergencyNumber = prefs.getString("emergency_number", "");
    if (emergencyNumber.trim()) {
      window.setTimeout(() => void callEmergency(), 3000);
    }
    void sendEmergencySMS();
  }, [addToHistory, callEmergency, sendEmergencySMS, speakResponse, updateStatus]);

  const handleHealthButton = useCallback(() => {
    const healthMsg = getHealthAnnouncement();
    addToHistory(`${prefs.getString("assistant_name", "Kandar")}: ${healthMsg}`);
    speakResponse(healthMsg, 1.0, 0.8);
  }, [addToHistory, speakResponse]);

  const getBatteryMsg = useCallback(() => {
    const level = lastBatteryRef.current;
    let msg = `Baterai HP ${prefs.getString("nenek_name", "Bu")} sekarang ${level} persen.`;
    if (level <= 20) msg += " Sudah rendah, sebaiknya segera dicharge.";
    else if (level <= 50) msg += " Masih cukup.";
    else msg += " Aman.";
    return msg;
  }, []);

  const handleLocalAction = useCallback(
    (action: string) => {
      const nenek = prefs.getString("nenek_name", "Bu");
      switch (action) {
        case "get_greeting":
          processLocalResponse(getGreetingText());
          break;
        case "get_time":
          processLocalResponse(getTimeText());
          break;
        case "get_date":
          processLocalResponse(getDateText());
          break;
        case "repeat_last": {
          const last = lastSpokenRef.current;
          processLocalResponse(last ? last : `Belum ada obrolan nih ${nenek}.`);
          break;
        }
        case "get_weather":
          updateStatus("Mencari lokasi...");
          void fetchWeatherWithLocation(false);
          break;
        case "get_forecast":
          updateStatus("Mencari lokasi...");
          void fetchWeatherWithLocation(true);
          break;
        case "trigger_emergency":
          handlePanicSituation();
          break;
        case "get_health":
          handleHealthButton();
          break;
        case "get_prayer_times":
          processLocalResponse(prayer.scheduleSpeech(nenek));
          break;
        case "get_next_prayer":
          processLocalResponse(prayer.nextPrayerSpeech(nenek));
          break;
        case "get_help":
          processLocalResponse(HELP_TEXT);
          break;
        case "get_battery":
          processLocalResponse(getBatteryMsg());
          break;
        case "get_favorite_contact":
          void handleFavoriteContact();
          break;
        default:
          processLocalResponse(`Oke ${nenek}.`);
          break;
      }
    },
    [
      fetchWeatherWithLocation, getBatteryMsg, handleFavoriteContact,
      handleHealthButton, handlePanicSituation, processLocalResponse, updateStatus,
    ],
  );

  // ================= ALUR INTI =================

  const handleStopCommand = useCallback(() => {
    void bridge.stopSpeaking();
    updateStatus("Siap");
    const nenek = prefs.getString("nenek_name", "Bu");
    addToHistory(`${prefs.getString("assistant_name", "Kandar")}: Sampai jumpa, ${nenek}.`);
    speakResponse(`Sampai jumpa, ${nenek}. Aku di sini kalau butuh ya.`);
  }, [addToHistory, speakResponse, updateStatus]);

  const handleRepeatCommand = useCallback(() => {
    if (!lastSpokenRef.current) {
      processLocalResponse(`Belum ada obrolan nih ${prefs.getString("nenek_name", "Bu")}.`);
      return;
    }
    speakResponse(lastSpokenRef.current);
  }, [processLocalResponse, speakResponse]);

  const processInput = useCallback(
    (rawInput: string) => {
      const raw = rawInput.toLowerCase().trim();
      if (!raw) return;
      // guard input cepat: input identik dalam <1,5 dtk = duplikat event
      // (enter dobel / transkripsi ganda) — proses sekali saja
      const now = Date.now();
      if (lastInputRef.current.raw === raw && now - lastInputRef.current.at < 1500) return;
      lastInputRef.current = { raw, at: now };
      addToHistory(`Anda: ${raw}`);

      // tanda hidup: batalkan eskalasi cek harian
      cancelEscalation();

      if (raw.includes("berhenti") || raw.includes("stop") || raw.includes("selesai")) {
        handleStopCommand();
        return;
      }
      if (raw.includes("ulangi") || raw.includes("ulang")) {
        handleRepeatCommand();
        return;
      }
      if (
        raw.includes("telpon") || raw.includes("telepon") || raw.includes("telfon") ||
        raw.includes("call") || raw.includes("nelpon")
      ) {
        if (raw.includes("anak") || raw.includes("cucu") || raw.includes("favorit") || raw.includes("kesayangan")) {
          void handleFavoriteContact();
          return;
        }
      }
      if (raw.includes("baterai") || raw.includes("battery")) {
        processLocalResponse(getBatteryMsg());
        return;
      }

      const engine = engineRef.current!;
      // alur companion lokal (kuis, doa, nostalgia, obat, lagu, berita, memori)
      if (engine.handleQuickCommands(raw)) return;

      const matcher = matcherRef.current!;

      if (!hasInternet()) {
        const localResult = matcher.match(raw);
        if (localResult.action) {
          handleLocalAction(localResult.action);
        } else if (localResult.response) {
          processLocalResponse(
            localResult.response
              .replace(/{nenek_name}/g, prefs.getString("nenek_name", "Bu"))
              .replace(/{assistant_name}/g, prefs.getString("assistant_name", "Kandar")),
          );
        } else {
          processLocalResponse(
            `Maaf ${prefs.getString("nenek_name", "Bu")}, tidak ada koneksi internet. Coba tanya hal lain.`,
          );
        }
        return;
      }

      updateStatus("Berpikir...");

      // rute lokal dulu: matcher lokal = output identik dgn [ACTION:]/respons
      // JSON (hemat token & tahan rate-limit); LLM hanya buat obrolan bebas.
      const local = matcher.match(raw);
      if (local.tag !== "unknown" && local.tag !== "fallback") {
        if (local.action) {
          handleLocalAction(local.action);
          return;
        }
        if (local.response && local.response.trim()) {
          processLocalResponse(
            local.response
              .replace(/{nenek_name}/g, prefs.getString("nenek_name", "Bu"))
              .replace(/{assistant_name}/g, prefs.getString("assistant_name", "Kandar")),
          );
          return;
        }
      }

      getResponse(
        raw,
        prefs.getString("nenek_name", "Bu"),
        prefs.getString("assistant_name", "Kandar"),
        "", // intent sudah ditangani matcher lokal di atas
        memoryRef.current.getPromptBlock(),
        prefs.getString(HISTORY_KEY, "")
          .split("\n")
          .map((l) => {
            const t = l.trim();
            if (!t) return "";
            const idx = t.indexOf("] ");
            return idx >= 0 ? t.substring(idx + 2).trim() : t;
          })
          .filter(Boolean),
        {
          onSuccess: (response) => {
            if (response.startsWith("[ACTION:")) {
              const close = response.indexOf("]");
              const action = response.substring(8, close > 0 ? close : response.length);
              handleLocalAction(action);
            } else {
              processLocalResponse(response);
              engine.onAssistantResponse(raw, response);
            }
          },
          onError: (error) => {
            const result = matcher.match(raw);
            if (result.action) {
              handleLocalAction(result.action);
            } else if (result.response) {
              processLocalResponse(
                result.response
                  .replace(/{nenek_name}/g, prefs.getString("nenek_name", "Bu"))
                  .replace(/{assistant_name}/g, prefs.getString("assistant_name", "Kandar")),
              );
            } else {
              processLocalResponse(error);
            }
          },
        },
      );
    },
    [
      addToHistory, getBatteryMsg, handleFavoriteContact, handleLocalAction,
      handleRepeatCommand, handleStopCommand, processLocalResponse, updateStatus,
    ],
  );

  // ================= VOICE LOOP (VAD -> WHISPER) =================

  const startListeningLoop = useCallback(async () => {
    if (listeningRef.current) return;
    if (!autoListenRef.current) return;
    // KUNCI dulu sebelum await: kalau menunggu hasil promise, dua pemanggilan
    // berdekatan (mis. timer listen ganda dari sapaan dobel) sama-sama lolos
    // guard dan bikin dua pipeline dengar paralel -> input diproses 2x.
    listeningRef.current = true;
    const unlock = () => {
      listeningRef.current = false;
      setListening(false);
    };
    const granted = await bridge.hasMicPermission();
    if (!granted) {
      unlock();
      return;
    }
    // kalau Mode Teman native (foreground service) sedang jalan,
    // biar dia yang dengar — jangan dobel dengar sama WebView
    if (await bridge.isCompanionRunning()) {
      unlock();
      updateStatus("Mode Teman sedang aktif - asisten dengar terus");
      return;
    }
    // Jangan rekam selama TTS masih bersuara (termasuk TTS receiver native
    // seperti sapaan proaktif/reminder): kalau tidak, mic menangkap gema
    // asisten sendiri -> Whisper mentranskrip -> asisten "menjawab dirinya sendiri".
    const ttsDeadline = Date.now() + 10000;
    while (Date.now() < ttsDeadline) {
      if (stopFlagRef.current) {
        stopFlagRef.current = false;
        unlock();
        updateStatus("Siap");
        return;
      }
      if (!(await bridge.ttsIsSpeaking())) break;
      await new Promise((r) => window.setTimeout(r, 200));
    }
    // jeda tenang biar ekor audio / gema loudspeaker meredam dulu
    await new Promise((r) => window.setTimeout(r, 700));
    if (stopFlagRef.current) {
      stopFlagRef.current = false;
      unlock();
      updateStatus("Siap");
      return;
    }
    setListening(true);
    updateStatus("Mendengarkan...");
    try {
      const pcm = await recordWithVad(() => stopFlagRef.current);
      if (stopFlagRef.current) {
        stopFlagRef.current = false;
        listeningRef.current = false;
        setListening(false);
        updateStatus("Siap");
        return;
      }
      if (!pcm || pcm.length === 0) {
        listeningRef.current = false;
        setListening(false);
        updateStatus("Siap");
        scheduleListening(300);
        return;
      }
      updateStatus("Mengenali suara...");
      const wav = encodeWav(pcm, SAMPLE_RATE);
      const file = new File([wav], "audio.wav", { type: "audio/wav" });
      transcribeAudio(file, {
        onSuccess: (text) => {
          listeningRef.current = false;
          setListening(false);
          updateStatus("Siap");
          const t = (text ?? "").trim();
          if (!t) {
            scheduleListening(300);
            return;
          }
          // gerbang 1: transkrip ternyata gema ucapan asisten sendiri
          if (looksLikeEcho(t, lastSpokenRef.current)) {
            scheduleListening(300);
            return;
          }
          // gerbang 2: mic menghasilkan teks identik lagi <30 dtk — hampir
          // pasti gema/halusinasi beruntun, bukan user mengulang sungguhan
          const nrm = normalizeSpeech(t);
          const now = Date.now();
          if (
            lastMicInputRef.current.raw === nrm &&
            now - lastMicInputRef.current.at < 30000
          ) {
            scheduleListening(300);
            return;
          }
          lastMicInputRef.current = { raw: nrm, at: now };
          processInput(t);
        },
        onError: () => {
          listeningRef.current = false;
          setListening(false);
          updateStatus("Gagal mengenali suara");
          scheduleListening(1000);
        },
      });
    } catch {
      listeningRef.current = false;
      setListening(false);
      updateStatus("Siap");
      scheduleListening(1000);
    }
  }, [processInput, scheduleListening, updateStatus]);

  const stopListening = useCallback(() => {
    stopFlagRef.current = true;
  }, []);

  // rekam sekali (tombol tekan-mic): sama saja dgn loop sekali
  const tapMic = useCallback(() => {
    if (listeningRef.current) {
      stopFlagRef.current = true;
      return;
    }
    autoListenRef.current = true;
    void startListeningLoop();
  }, [startListeningLoop]);

  // ================= LIFECYCLE =================

  useEffect(() => {
    loadSavedHistory();

    let cancelled = false;
    const timers: number[] = [];
    // kalau loop instance lama masih hidup setelah unmount (mis. remount
    // dari settings), hentikan — biar tidak ada dua pipeline dengar.
    stopFlagRef.current = false;

    void (async () => {
      // lokasi & jadwal adzan
      if (prayer.isEnabled()) {
        const loc = await bridge.getLastLocation();
        if (loc) {
          prefs.setJson("last_lat", loc.lat);
          prefs.setJson("last_lon", loc.lon);
        }
        prayer.refreshIfNeededAsync();
      }
      // baterai
      const level = await bridge.getBatteryLevel();
      if (level >= 0) {
        lastBatteryRef.current = level;
        if (level <= 20 && !batteryWarnedRef.current) {
          batteryWarnedRef.current = true;
          timers.push(
            window.setTimeout(() => {
              if (cancelled) return;
              const warning =
                `Perhatian, baterai HP tinggal ${level} persen. Sebaiknya segera dicharge ya ` +
                `${prefs.getString("nenek_name", "Bu")}.`;
              addToHistory(`${prefs.getString("assistant_name", "Kandar")}: [Baterai] ${warning}`);
              speakResponse(warning, 1.0, 0.8);
            }, 2000),
          );
        }
      }

      // salam awal — hanya SEKALI per sesi app (sessionStorage), bukan tiap
      // mount/remount MainScreen, dan timer dibatalkan saat cleanup efek
      // (StrictMode menjalankan efek 2x di dev tanpa cleanup -> salam dobel).
      timers.push(
        window.setTimeout(() => {
          if (cancelled) return;
          const isFirstTime = prefs.getBoolean("is_first_time", true);
          if (isFirstTime) {
            prefs.setBoolean("is_first_time", false);
            return; // App routing ke wizard
          }
          greetedRef.current = true;
          if (sessionStorage.getItem("greeted") === "1") {
            // sesi sama: sudah pernah salam, cukup lanjutkan listen
            scheduleListening(500);
            return;
          }
          sessionStorage.setItem("greeted", "1");
          let greetingText = getFollowupQuestion(prefs.getString("nenek_name", "Bu"));
          if (greetingText !== null) {
            greetingText = `Oh iya ${prefs.getString("nenek_name", "Bu")}, ${greetingText}`;
          } else {
            greetingText = `Halo ${prefs.getString("nenek_name", "Bu")}, ${prefs.getString("assistant_name", "Kandar")} sudah siap temani ngobrol nih. Silakan bicara.`;
          }
          addToHistory(`${prefs.getString("assistant_name", "Kandar")}: ${greetingText}`);
          speakResponse(greetingText, 0.85, 0.75);
        }, 1000),
      );
    })();

    return () => {
      cancelled = true;
      for (const t of timers) window.clearTimeout(t);
      stopFlagRef.current = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // alarm browser (fallback non-native): petakan ke handler receiver
  useEffect(() => {
    const ui: ReceiverUI = { speak: speakResponse, addToHistory, updateStatus };
    bridge.setBrowserAlarmHandler((kind, index, code) => {
      void handleAlarmEvent(kind, index, code, ui);
    });
    return () => bridge.setBrowserAlarmHandler(null);
  }, [addToHistory, speakResponse, updateStatus]);

  // ================= TOGGLES =================

  const toggleCompanion = useCallback(async () => {
    const on = prefs.getBoolean("keep_alive", false);
    if (on) {
      await bridge.stopCompanion();
      prefs.setBoolean("keep_alive", false);
      setCompanionOn(false);
    } else {
      prefs.setBoolean("keep_alive", true);
      const ok = await bridge.startCompanion();
      setCompanionOn(ok || bridge.isNativePlatform() === false);
    }
  }, []);

  const emergency = useCallback(async () => {
    await callEmergency();
  }, [callEmergency]);

  const healthButton = useCallback(() => handleHealthButton(), [handleHealthButton]);
  const weatherButton = useCallback(() => void fetchWeatherWithLocation(false), [fetchWeatherWithLocation]);

  return {
    statusText,
    statusKind,
    chatLines,
    listening,
    companionOn,
    lastSpokenText,
    greetingText: greeting(
      ConversationEngine.getTimeOfDay(),
      prefs.getString("nenek_name", "Bu"),
      prefs.getString("assistant_name", "Kandar"),
    ),
    checkInEnabled: prefs.getBoolean("checkin_enabled", false),
    checkInTime: `${String(prefs.getInt("checkin_hour", 9)).padStart(2, "0")}:${String(prefs.getInt("checkin_minute", 0)).padStart(2, "0")}`,
    processInput,
    tapMic,
    stopListening,
    emergency,
    healthButton,
    weatherButton,
    toggleCompanion,
    updateStatus,
    speakResponse,
    addToHistory,
  };
}
