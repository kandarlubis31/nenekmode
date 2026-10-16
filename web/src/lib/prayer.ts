/**
 * Util jadwal adzan: cache harian, penjadwalan alarm 5 waktu,
 * dan helper teks buat dibacakan (jadwal lengkap / waktu berikutnya).
 * Port PrayerTimes.java + PrayerTimeClient.java.
 *
 * Alarm dipompa lewat registered callback (nanti diisi native plugin
 * via Capacitor); fetch jadwal pakai API Aladhan (gratis, KEMENAG).
 */

import { prefs } from "./prefs";

/** [0] = nama Indonesia buat suara, [1] = key di API Aladhan. */
export const PRAYERS: Array<[string, string]> = [
  ["Subuh", "Fajr"],
  ["Dzuhur", "Dhuhr"],
  ["Ashar", "Asr"],
  ["Maghrib", "Maghrib"],
  ["Isya", "Isha"],
];

const KEY = "prayer_times_cache";
const BASE = "https://api.aladhan.com/v1/timings/";

interface PrayerCache {
  date: string;
  times: Record<string, string>;
}

/** Bridge alarm — dipasang native layer. Tanpa bridge, scheduling no-op. */
export interface PrayerAlarmBridge {
  scheduleExact(epochMs: number, requestCode: number, kind: string, index: number): void;
  cancelAll(): void;
}

let alarmBridge: PrayerAlarmBridge | null = null;

export function setPrayerAlarmBridge(b: PrayerAlarmBridge | null): void {
  alarmBridge = b;
}

// ================= CACHE =================

function getCache(): PrayerCache | null {
  try {
    const raw = prefs.getString(KEY, "");
    if (!raw) return null;
    return JSON.parse(raw) as PrayerCache;
  } catch {
    return null;
  }
}

function saveCache(date: string, times: Record<string, string>): void {
  prefs.setString(KEY, JSON.stringify({ date, times }));
}

export function isEnabled(): boolean {
  return prefs.getBoolean("adzan_enabled", true);
}

function todayStr(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getDate())}-${p(d.getMonth() + 1)}-${d.getFullYear()}`;
}

export function isStale(): boolean {
  const c = getCache();
  if (!c) return true;
  return todayStr() !== c.date;
}

/** Waktu satu sholat (nama Indonesia), format "HH:mm" atau "" jika kosong. */
export function timeOf(displayName: string): string {
  const c = getCache();
  if (!c) return "";
  for (const [indonesian, key] of PRAYERS) {
    if (indonesian.toLowerCase() === displayName.toLowerCase()) {
      return (c.times[key] ?? "").trim();
    }
  }
  return "";
}

function toMinutes(hhmm: string): number {
  try {
    const p = hhmm.split(":");
    const h = parseInt(p[0].trim(), 10);
    const m = parseInt(p[1].trim().replace(/[^0-9].*$/, ""), 10);
    if (Number.isNaN(h) || Number.isNaN(m)) return -1;
    return h * 60 + m;
  } catch {
    return -1;
  }
}

/** "04:23" -> "jam 4 lewat 23" (enak dibaca TTS Indonesia). */
export function fmt(hhmm: string): string {
  const mins = toMinutes(hhmm);
  if (mins < 0) return "pukul " + hhmm;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m === 0 ? `jam ${h}` : `jam ${h} lewat ${m}`;
}

// ================= PENJADWALAN =================

/** Pasang alarm untuk semua waktu sholat hari ini yang belum lewat + refresh besok. */
export function scheduleDay(): void {
  if (!isEnabled()) return;
  const c = getCache();
  if (!c) return;

  const now = new Date();
  const nowMin = now.getHours() * 60 + now.getMinutes();
  try {
    for (let i = 0; i < PRAYERS.length; i++) {
      const tMin = toMinutes((c.times[PRAYERS[i][1]] ?? "").trim());
      if (tMin < 0 || tMin <= nowMin) continue;
      const cal = new Date(now);
      cal.setHours(Math.floor(tMin / 60), tMin % 60, 0, 0);
      alarmBridge?.scheduleExact(cal.getTime(), 1100 + i, "prayer", i);
    }
  } catch {
    /* abaikan */
  }

  scheduleRefresh();
}

/** Refresh harian jam 00:10 — waktu sholat bergeser tiap hari. */
export function scheduleRefresh(): void {
  const cal = new Date();
  cal.setDate(cal.getDate() + 1);
  cal.setHours(0, 10, 0, 0);
  alarmBridge?.scheduleExact(cal.getTime(), 1150, "refresh", 0);
}

export function cancelAll(): void {
  alarmBridge?.cancelAll();
}

/**
 * Ambil jadwal baru kalau stale (pakai koordinat terakhir / default Jakarta),
 * lalu pasang alarm hari ini. onDone boleh null.
 */
export function refreshIfNeededAsync(onDone?: () => void): void {
  if (!isEnabled()) return;
  if (!isStale()) {
    scheduleDay();
    onDone?.();
    return;
  }
  const lat = prefs.getNumber("last_lat", -6.2);
  const lon = prefs.getNumber("last_lon", 106.8);
  fetchSync(lat, lon)
    .then((fresh) => {
      if (fresh) saveCache(fresh.date, fresh.times);
    })
    .catch(() => undefined)
    .finally(() => {
      scheduleDay();
      onDone?.();
    });
}

/** Ambil jadwal hari ini dari Aladhan (method 20 = KEMENAG). */
export async function fetchSync(
  lat: number,
  lon: number,
): Promise<{ date: string; times: Record<string, string> } | null> {
  try {
    const url =
      BASE + todayStr() + "?latitude=" + lat + "&longitude=" + lon + "&method=20";
    const resp = await fetch(url, {
      headers: { "User-Agent": "Mozilla/5.0" },
      signal: AbortSignal.timeout(10000),
    });
    if (!resp.ok) return null;
    const root = (await resp.json()) as {
      data?: {
        timings?: Record<string, string>;
        date?: { gregorian?: { date?: string } };
      };
    };
    const data = root.data;
    if (!data?.timings || !data.date?.gregorian?.date) return null;

    // bersihkan nilai "04:23 (WIB)" -> "04:23"
    const clean: Record<string, string> = {};
    for (const [k, v0] of Object.entries(data.timings)) {
      let v = (v0 ?? "").trim();
      const sp = v.indexOf(" ");
      if (sp > 0) v = v.substring(0, sp);
      clean[k] = v;
    }
    return { date: data.date.gregorian.date, times: clean };
  } catch {
    return null;
  }
}

// ================= TEKS SUARA =================

/** Jadwal lengkap hari ini buat dibacakan. */
export function scheduleSpeech(elderName: string): string {
  const parts: string[] = [];
  for (const [name] of PRAYERS) {
    const t = timeOf(name);
    if (t) parts.push(`${name} ${fmt(t)}`);
  }
  if (parts.length === 0) {
    return `Belum bisa ambil jadwal adzan. Coba lagi nanti ya ${elderName}.`;
  }
  return `Jadwal adzan hari ini: ${parts.join(", ")}. Jangan lupa sholat ya ${elderName}.`;
}

/** Satu baris pendek buat Mode papan info: "Sholat berikutnya: Maghrib 17:45". */
export function nextPrayerShort(): string {
  const c = getCache();
  if (!c) return "Sholat berikutnya: (jadwal belum tersedia)";
  const now = new Date();
  const nowMin = now.getHours() * 60 + now.getMinutes();
  try {
    for (const [name, key] of PRAYERS) {
      const raw = (c.times[key] ?? "").trim();
      const tMin = toMinutes(raw);
      if (tMin < 0) continue;
      if (tMin > nowMin) {
        return `Sholat berikutnya: ${name} ${raw}`;
      }
    }
  } catch {
    /* abaikan */
  }
  return "Semua waktu sholat hari ini sudah lewat";
}

/** Info waktu sholat terdekat / yang sedang masuk. */
export function nextPrayerSpeech(elderName: string): string {
  const c = getCache();
  if (!c) return `Belum ada data jadwal adzan, coba lagi nanti ya ${elderName}.`;

  const now = new Date();
  const nowMin = now.getHours() * 60 + now.getMinutes();
  try {
    for (const [name, key] of PRAYERS) {
      const tMin = toMinutes((c.times[key] ?? "").trim());
      if (tMin < 0) continue;
      if (nowMin >= tMin && nowMin < tMin + 30) {
        return `Sudah masuk waktu sholat ${name} ya ${elderName}. Yuk sholat dulu.`;
      }
      if (tMin > nowMin) {
        const diff = tMin - nowMin;
        let when: string;
        if (diff >= 60) {
          const h = Math.floor(diff / 60);
          const m = diff % 60;
          when = m > 0 ? `${h} jam lebih ${m} menit` : `${h} jam`;
        } else {
          when = `${diff} menit`;
        }
        return `Adzan ${name} sekitar ${when} lagi ya ${elderName}.`;
      }
    }
  } catch {
    /* abaikan */
  }
  return `Semua waktu sholat hari ini sudah lewat. Besok ada lagi ya ${elderName}.`;
}
