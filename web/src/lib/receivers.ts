/**
 * Logika saat alarm mati — port ReminderReceiver, MedConfirmReceiver,
 * CheckInReceiver, AdzanReceiver, WaterReceiver, BirthdayReceiver,
 * ProactiveReceiver. Dipakai fallback browser; di APK versi native plugin
 * yang mengeksekusi (kontrak perilaku identik).
 */

import { prefs } from "./prefs";
import { ConversationEngine } from "./conversation";
import { greeting } from "./greetings";
import * as prayer from "./prayer";
import { weather } from "./weather";
import { news } from "./news";
import { getMedTimes } from "./actions";
import { arm, trigger } from "./escalation";
import {
  CODES,
  cancelCheckIn,
  proactiveRescheduleNext,
  scheduleBirthdayNext,
  scheduleSlot,
  scheduleWaterNext,
  setCheckIn,
  setReminders,
  rescheduleAllOnStart,
} from "./scheduling";
import * as bridge from "../native/bridge";

export interface ReceiverUI {
  /** TTS ala SpeechUtil (bicara lewat TTS aktif). */
  speak(text: string): void;
  addToHistory(message: string): void;
  updateStatus(status: string): void;
}

function pad2(n: number): string {
  return n < 10 ? "0" + n : String(n);
}

function notify(title: string, body: string): void {
  try {
    if ("Notification" in window && Notification.permission === "granted") {
      new Notification(title, { body });
    }
  } catch {
    /* abaikan */
  }
}

// ================= REMINDER OBAT =================

function fireReminder(ui: ReceiverUI, index: number): void {
  const elderName = prefs.getString("nenek_name", "Bu");
  const times = getMedTimes();
  let h = 8;
  let m = 0;
  const t = times[index];
  if (t) {
    h = t.h;
    m = t.m;
  }

  const msg = prefs.getString("reminder_message", "Sudah waktunya minum obat ya!");
  const text = `${msg} (${pad2(h)}:${pad2(m)}) - ${elderName}`;
  notify("Pengingat Obat", text);

  // suara (variasi hangat, jangan monoton)
  const tod = ConversationEngine.getTimeOfDay();
  const greet = greeting(tod, elderName, prefs.getString("assistant_name", "Kandar"));
  ui.speak(`${greet} Oh iya, ${msg}`);

  // aktifkan alur konfirmasi suara + jadwalkan follow-up 15 menit
  prefs.setBoolean("med_confirm_waiting", true);
  void bridge.scheduleAlarm({
    code: CODES.confirmBase + index,
    epochMs: Date.now() + 15 * 60 * 1000,
    kind: "reminder_confirm",
    index,
  });
}

function fireReminderConfirm(ui: ReceiverUI, index: number): void {
  if (prefs.getBoolean("med_confirm_waiting", false)) {
    const elderName = prefs.getString("nenek_name", "Bu");
    ui.speak(
      `${elderName}, kalau sudah minum obat bilang 'sudah' ya, ` +
        `biar hati ${prefs.getString("assistant_name", "Kandar")} tenang.`,
    );
  }

  // jadwalkan ulang slot ini untuk besok
  try {
    const t = getMedTimes()[index];
    if (t && t.en !== false) scheduleSlot(index, t.h, t.m);
  } catch {
    /* abaikan */
  }
}

// ================= CEK HARIAN =================

function fireCheckIn(ui: ReceiverUI): void {
  const prefsLocal = prefs;
  const elderName = prefsLocal.getString("nenek_name", "Bu");
  const question = `Halo! Bagaimana kesehatan ${elderName} hari ini?`;
  ui.speak(`${question} Bilang aja kalau mau cerita.`);
  notify("Cek Harian", question);

  // jadwalkan ulang untuk besok
  setCheckIn(prefsLocal.getInt("checkin_hour", 9), prefsLocal.getInt("checkin_minute", 0));

  // jaring pengaman: alarm 30 menit, dibatalkan kalau nenek bicara
  arm();
}

// ================= ADZAN =================

function firePrayer(ui: ReceiverUI, index: number): void {
  if (!prayer.isEnabled()) return;
  if (index < 0 || index >= prayer.PRAYERS.length) return;
  const prayerName = prayer.PRAYERS[index][0];
  const elderName = prefs.getString("nenek_name", "Bu");
  const line =
    `Assalamualaikum ${elderName}, sudah masuk waktu sholat ${prayerName} ` +
    "untuk wilayah sekitar. Yuk kita sholat dulu ya.";
  ui.speak(line);
  notify(`Waktu Sholat ${prayerName}`, line);
}

// ================= AIR MINUM =================

function fireWater(ui: ReceiverUI): void {
  if (!prefs.getBoolean("water_enabled", false)) return;

  // jangan ganggu saat mode malam
  if (!ConversationEngine.isNightMode()) {
    const elderName = prefs.getString("nenek_name", "Bu");
    const line = `${elderName}, yuk minum dulu segelas air ya. Biar badannya segar.`;
    ui.speak(line);
    notify("Waktu Minum", line);
    prefs.setBoolean("water_confirm_waiting", true);
  }

  scheduleWaterNext();
}

// ================= ULANG TAHUN =================

function fireBirthday(ui: ReceiverUI): void {
  if (!prefs.getBoolean("birthday_enabled", false)) {
    scheduleBirthdayNext();
    return;
  }

  const d = new Date();
  const today = `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  try {
    const arr = JSON.parse(prefs.getString("birthday_list", "[]")) as string[];
    for (const date of arr) {
      if (date !== today) continue;
      const elderName = prefs.getString("nenek_name", "Bu");
      const assistantName = prefs.getString("assistant_name", "Kandar");
      let line: string;
      if (date === "12-31" || date === "01-01") {
        line = `Selamat tahun baru ${elderName}! Semoga tahun ini penuh kesehatan dan kebahagiaan ya.`;
      } else {
        line =
          `Selamat ulang tahun ${elderName}! Semoga Tuhan selalu memberi kesehatan, ` +
          `kebahagiaan, dan panjang umur. ${assistantName} ikut berbahagia hari ini.`;
      }
      ui.speak(line);
      notify("Hari Spesial", line);
    }
  } catch {
    /* abaikan */
  }

  scheduleBirthdayNext();
}

// ================= PROAKTIF =================

async function fireProactive(ui: ReceiverUI): Promise<void> {
  if (!prefs.getBoolean("companion_enabled", false)) return; // fitur dimatikan
  if (ConversationEngine.isNightMode()) {
    // mode malam: jangan ganggu
    proactiveRescheduleNext();
    return;
  }

  const elderName = prefs.getString("nenek_name", "Bu");
  const assistantName = prefs.getString("assistant_name", "Kandar");
  const tod = ConversationEngine.getTimeOfDay();
  let line = greeting(tod, elderName, assistantName);

  // variasi harian: selasa & kamis ajak kuis, rabu & jumat ajak nostalgia
  const dow = new Date().getDay(); // 0 = Minggu
  if (dow === 2 || dow === 4) {
    line += " Kalau mau, bilang aja 'mulai kuis'.";
  } else if (dow === 3 || dow === 5) {
    line += " Kalau mau, kita cerita zaman dulu aja. Bilang aja 'cerita dulu'.";
  }

  // peringatan cuaca pagi (opsional)
  if (tod === "pagi" && prefs.getBoolean("weather_alert_enabled", false)) {
    try {
      const lat = prefs.getNumber("last_lat", -6.2);
      const lon = prefs.getNumber("last_lon", 106.8);
      const warn = await weather.getWeatherWarningSync(lat, lon);
      if (warn) line += " " + warn;
    } catch {
      /* abaikan */
    }
  }

  // berita pagi (opsional)
  if (tod === "pagi" && prefs.getBoolean("news_enabled", true)) {
    try {
      const headlines = await news.getTopHeadlines();
      if (headlines.length > 0) {
        let sb = line + " Ini ada berita pagi. ";
        const n = Math.min(3, headlines.length);
        for (let i = 0; i < n; i++) sb += `Berita ${i + 1}: ${headlines[i]}. `;
        line = sb;
      }
    } catch {
      /* abaikan */
    }
  }

  ui.speak(line);
  notify(elderName, line);

  proactiveRescheduleNext();
}

// ================= DISPATCHER =================

export async function handleAlarmEvent(
  kind: string,
  index: number,
  code: number,
  ui: ReceiverUI,
): Promise<void> {
  switch (kind) {
    case "reminder":
      fireReminder(ui, index);
      break;
    case "reminder_snooze":
      fireReminder(ui, 0);
      break;
    case "reminder_confirm":
      fireReminderConfirm(ui, index);
      break;
    case "checkin":
      fireCheckIn(ui);
      break;
    case "escalation":
      await trigger();
      break;
    case "prayer":
      firePrayer(ui, index);
      break;
    case "refresh":
      prayer.refreshIfNeededAsync();
      break;
    case "water":
      fireWater(ui);
      break;
    case "birthday":
      fireBirthday(ui);
      break;
    case "proactive":
      await fireProactive(ui);
      break;
    case "watchdog":
      // verifikasi semua alarm masih terpasang (ala WatchdogReceiver)
      rescheduleAllOnStart();
      break;
    default:
      console.warn("Alarm event tak dikenal:", kind, code, index);
      break;
  }
}

/** Dipanggil settings saat toggle check-in berubah. */
export function applyCheckInSetting(enabled: boolean, hour: number, minute: number): void {
  if (enabled) setCheckIn(hour, minute);
  else cancelCheckIn();
}

/** Dipanggil settings saat toggle reminder obat berubah. */
export function applyReminderSetting(enabled: boolean): void {
  if (enabled) setReminders();
  else {
    void bridge.cancelAlarm(CODES.snooze);
    for (let i = 0; i < 3; i++) {
      void bridge.cancelAlarm(CODES.reminderBase + i);
      void bridge.cancelAlarm(CODES.confirmBase + i);
    }
  }
}
