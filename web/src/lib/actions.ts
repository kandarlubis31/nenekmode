/**
 * Helper aksi lokal murni (teks sapaan/waktu/tanggal/kesehatan).
 * Port dari metode MainActivity — tanpa side effect UI.
 */

import { prefs } from "./prefs";
import { ConversationEngine } from "./conversation";
import { bp } from "./bp";

const DAYS_ID = [
  "Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu",
];
const MONTHS_ID = [
  "Januari", "Februari", "Maret", "April", "Mei", "Juni",
  "Juli", "Agustus", "September", "Oktober", "November", "Desember",
];

export function getGreetingText(): string {
  const nenekName = prefs.getString("nenek_name", "Bu");
  const hour = new Date().getHours();
  if (hour >= 4 && hour < 11) return `Selamat pagi ${nenekName}! Sehat-sehat aja?`;
  if (hour >= 11 && hour < 15) return `Selamat siang ${nenekName}! Gimana hari ${nenekName} hari ini?`;
  if (hour >= 15 && hour < 18) return `Selamat sore ${nenekName}! Udah sore nih, udah istirahat?`;
  return `Selamat malam ${nenekName}! Semoga nyenyak tidurnya nanti.`;
}

export function getTimeText(): string {
  const now = new Date();
  const hour = now.getHours();
  const minute = now.getMinutes();
  const tod = ConversationEngine.getTimeOfDay();
  const dh = hour > 12 ? hour - 12 : hour === 0 ? 12 : hour;
  const mStr = minute > 0 ? ` lewat ${minute} menit` : "";
  return `Sekarang jam ${dh}${mStr} ${tod} ya ${prefs.getString("nenek_name", "Bu")}.`;
}

export function getDateText(): string {
  const now = new Date();
  const day = DAYS_ID[now.getDay()];
  const date = `${now.getDate()} ${MONTHS_ID[now.getMonth()]} ${now.getFullYear()}`;
  return `Hari ini hari ${day}, tanggalnya ${date} ${prefs.getString("nenek_name", "Bu")}.`;
}

export interface MedTime {
  h: number;
  m: number;
  en: boolean;
}

export function defaultMedTimes(): MedTime[] {
  return [
    { h: 8, m: 0, en: true },
    { h: 14, m: 0, en: false },
    { h: 20, m: 0, en: false },
  ];
}

export function getMedTimes(): MedTime[] {
  try {
    const raw = prefs.getString("med_times", "");
    if (!raw) return defaultMedTimes();
    const arr = JSON.parse(raw) as MedTime[];
    return Array.isArray(arr) ? arr : defaultMedTimes();
  } catch {
    return defaultMedTimes();
  }
}

function pad2(n: number): string {
  return n < 10 ? "0" + n : String(n);
}

/** Info kesehatan untuk tombol: jadwal obat + tensi terakhir + streak. */
export function getHealthAnnouncement(): string {
  const nenekName = prefs.getString("nenek_name", "Bu");
  const times = getMedTimes();
  let sb = "Jadwal obat: ";
  let any = false;
  for (const t of times) {
    if (t.en === false) continue;
    if (any) sb += ", ";
    sb += `${pad2(t.h)}:${pad2(t.m)}`;
    any = true;
  }
  if (!any) sb += "belum ada yang aktif. Buka pengaturan untuk atur.";
  else sb += `. Jangan lupa minum obat ya ${nenekName}. Semoga cepat sehat!`;

  if (prefs.getBoolean("bp_enabled", false)) {
    const e = bp.latest();
    if (e) {
      sb += ` Tensi darah terakhir dicatat ${e.date}: ${e.sys} atas ${e.dia}.`;
      sb += e.sys >= 140 || e.dia >= 90
        ? " Itu agak tinggi, jangan lupa kontrol ke dokter ya."
        : " Alhamdulillah masih bagus.";
      if (bp.count() > 1) sb += " " + bp.speechHistory();
    } else {
      sb += " Belum ada catatan tensi darah. Kalau sudah diukur, bilang aja, misalnya tensi darahku 130 atas 85.";
    }
  }

  const streak = prefs.getInt("med_streak", 0);
  if (streak >= 3) {
    sb += ` Kamu udah ${streak} hari berturut-turut minum obat tepat waktu, luar biasa ${nenekName}!`;
  }
  return sb;
}

export const HELP_TEXT =
  "Bisa tanya jam berapa, cuaca, tanggal, baterai, bilang mulai kuis, bilang cerita dulu, " +
  "minta doa, atau minta putar lagu. Bilang berita buat dengar kabar. Bilang berhenti buat selesai.";

/** Pembersih kueri lagu: "puter lagu rasa sayang" -> "rasa sayang". */
export function cleanMusicQuery(rawQuery: string): string {
  const clean = rawQuery
    .replace(/puterin|puter|putar|dengerin|musik|lagu/gi, "")
    .trim();
  return clean.length > 0 ? clean : "lagu nostalgia indonesia";
}
