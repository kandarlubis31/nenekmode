/**
 * Jembatan ke plugin native "NenekNative" (TTS, alarm, SMS, telpon, SIM,
 * Mode Teman, battery). Di browser semua fallback aman biar dev tetap jalan.
 */

import { Capacitor, registerPlugin } from "@capacitor/core";

export interface SpeakOptions {
  text: string;
  pitch?: number;
  rate?: number;
  volume?: number;
}

export interface ScheduleAlarmOptions {
  code: number; // request code unik (mirip PendingIntent requestCode)
  epochMs: number;
  kind: string; // "reminder" | "checkin" | "escalation" | "prayer" | "refresh" | ...
  index?: number;
}

export interface NenekNativePlugin {
  speak(opts: SpeakOptions): Promise<void>;
  stopSpeaking(): Promise<void>;
  isSpeaking(): Promise<{ speaking: boolean }>;
  scheduleAlarm(opts: ScheduleAlarmOptions): Promise<void>;
  cancelAlarm(opts: { code: number }): Promise<void>;
  cancelAllAlarms(): Promise<void>;
  sendSms(opts: { number: string; text: string }): Promise<{ ok: boolean; error?: string }>;
  callNumber(opts: { number: string }): Promise<{ ok: boolean; error?: string }>;
  hasActiveSim(): Promise<{ sim: boolean }>;
  startCompanion(): Promise<void>;
  stopCompanion(): Promise<void>;
  isCompanionRunning(): Promise<{ running: boolean }>;
  getBattery(): Promise<{ level: number }>;
  hasMicPermission(): Promise<{ granted: boolean }>;
  requestMicPermission(): Promise<{ granted: boolean }>;
  getGeo(): Promise<{ lat: number; lon: number } | null>;
  syncPrefs(opts: { data: string }): Promise<void>;
  pullPrefs(): Promise<Record<string, [string, unknown]>>;
  requestNotificationsPermission(): Promise<void>;
}

let native: NenekNativePlugin | null = null;
let nativeChecked = false;

function getNative(): NenekNativePlugin | null {
  if (!Capacitor.isNativePlatform()) return null;
  if (!nativeChecked) {
    try {
      if (Capacitor.isPluginAvailable("NenekNative")) {
        native = registerPlugin<NenekNativePlugin>("NenekNative");
      }
    } catch {
      native = null;
    }
    nativeChecked = true;
  }
  return native;
}

export function isNativePlatform(): boolean {
  return Capacitor.isNativePlatform();
}

export function hasNativeBridge(): boolean {
  return getNative() !== null;
}

async function safe<T>(fn: () => Promise<T>, fallback: T): Promise<T> {
  try {
    return await fn();
  } catch {
    return fallback;
  }
}

// ================= TTS =================

let browserSpeaking = false;

export async function speak(opts: SpeakOptions): Promise<void> {
  const n = getNative();
  if (n) {
    await safe(() => n.speak(opts), undefined as unknown as void);
    return;
  }
  // fallback browser: speechSynthesis — resolve saat TTS benar-benar selesai,
  // biar caller (voice loop) tidak membuka mic sambil asisten masih bersuara
  return await new Promise<void>((resolve) => {
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      browserSpeaking = false;
      resolve();
    };
    try {
      window.speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(opts.text);
      u.lang = "id-ID";
      u.pitch = opts.pitch ?? 0.85;
      u.rate = opts.rate ?? 0.75;
      u.volume = opts.volume ?? 1;
      browserSpeaking = true;
      u.onend = finish;
      u.onerror = finish;
      window.speechSynthesis.speak(u);
      // jaring pengaman: sebagian engine tidak memanggil onend
      window.setTimeout(finish, Math.max(5000, opts.text.length * 150));
    } catch {
      finish();
    }
  });
}

export async function stopSpeaking(): Promise<void> {
  const n = getNative();
  if (n) {
    await safe(() => n.stopSpeaking(), undefined as unknown as void);
    return;
  }
  try {
    window.speechSynthesis.cancel();
  } catch {
    /* abaikan */
  }
  browserSpeaking = false;
}

export async function ttsIsSpeaking(): Promise<boolean> {
  const n = getNative();
  if (n) {
    const r = await safe(() => n.isSpeaking(), { speaking: false });
    return r.speaking;
  }
  return browserSpeaking;
}

// ================= ALARM =================

const pendingTimeouts = new Map<number, number>();
export type AlarmHandler = (kind: string, index: number, code: number) => void;
let browserAlarmHandler: AlarmHandler | null = null;

/** Di browser: pasang handler alarm via setTimeout (hanya saat app terbuka). */
export function setBrowserAlarmHandler(h: AlarmHandler | null): void {
  browserAlarmHandler = h;
}

export async function scheduleAlarm(opts: ScheduleAlarmOptions): Promise<void> {
  const n = getNative();
  if (n) {
    await safe(() => n.scheduleAlarm(opts), undefined as unknown as void);
    return;
  }
  // fallback: setTimeout (dev browser / WebView foreground saja)
  const existing = pendingTimeouts.get(opts.code);
  if (existing) window.clearTimeout(existing);
  const delay = Math.max(0, opts.epochMs - Date.now());
  const id = window.setTimeout(() => {
    pendingTimeouts.delete(opts.code);
    browserAlarmHandler?.(opts.kind, opts.index ?? 0, opts.code);
  }, delay);
  pendingTimeouts.set(opts.code, id);
}

export async function cancelAlarm(code: number): Promise<void> {
  const n = getNative();
  if (n) {
    await safe(() => n.cancelAlarm({ code }), undefined as unknown as void);
    return;
  }
  const existing = pendingTimeouts.get(code);
  if (existing) {
    window.clearTimeout(existing);
    pendingTimeouts.delete(code);
  }
}

export async function cancelAllAlarms(): Promise<void> {
  const n = getNative();
  if (n) {
    await safe(() => n.cancelAllAlarms(), undefined as unknown as void);
    return;
  }
  for (const id of pendingTimeouts.values()) window.clearTimeout(id);
  pendingTimeouts.clear();
}

// ================= SMS / TELEPON =================

export async function sendSms(number: string, text: string): Promise<boolean> {
  if (!number || number.includes("XXXXXXXXXX")) return false;
  const n = getNative();
  if (n) {
    const r = await safe(() => n.sendSms({ number, text }), { ok: false });
    return r.ok;
  }
  // fallback browser: buka app SMS (gak silent, tapi tetap kepake)
  try {
    window.open(`sms:${number}?body=${encodeURIComponent(text)}`, "_blank");
    return true;
  } catch {
    return false;
  }
}

export async function callNumber(number: string): Promise<boolean> {
  if (!number) return false;
  const n = getNative();
  if (n) {
    const r = await safe(() => n.callNumber({ number }), { ok: false });
    return r.ok;
  }
  try {
    window.location.href = `tel:${number}`;
    return true;
  } catch {
    return false;
  }
}

export async function hasActiveSim(): Promise<boolean> {
  const n = getNative();
  if (n) {
    const r = await safe(() => n.hasActiveSim(), { sim: true });
    return r.sim;
  }
  return true; // fallback: jangan blokir
}

// ================= MODE TEMAN =================

export async function startCompanion(): Promise<boolean> {
  const n = getNative();
  if (!n) return false;
  try {
    await n.startCompanion();
    return true;
  } catch {
    return false;
  }
}

export async function stopCompanion(): Promise<boolean> {
  const n = getNative();
  if (!n) return false;
  try {
    await n.stopCompanion();
    return true;
  } catch {
    return false;
  }
}

export async function isCompanionRunning(): Promise<boolean> {
  const n = getNative();
  if (!n) return false;
  const r = await safe(() => n.isCompanionRunning(), { running: false });
  return r.running;
}

// ================= BATTERY / PERMISSION / LOCATION =================

export async function getBatteryLevel(): Promise<number> {
  const n = getNative();
  if (n) {
    const r = await safe(() => n.getBattery(), { level: -1 });
    if (r.level >= 0) return r.level;
  }
  try {
    const b = await (navigator as unknown as { getBattery?: () => Promise<{ level: number }> }).getBattery?.();
    if (b) return Math.round(b.level * 100);
  } catch {
    /* abaikan */
  }
  return -1;
}

export async function hasMicPermission(): Promise<boolean> {
  const n = getNative();
  if (n) {
    const r = await safe(() => n.hasMicPermission(), { granted: false });
    if (r.granted) return true;
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    for (const t of stream.getTracks()) t.stop();
    return true;
  } catch {
    return false;
  }
}

export async function requestMicPermission(): Promise<boolean> {
  const n = getNative();
  if (n) {
    const r = await safe(() => n.requestMicPermission(), { granted: false });
    if (r.granted) return true;
  }
  return hasMicPermission();
}

// ================= SINKRON PREFS (localStorage <-> SharedPreferences) =================

export async function syncPrefsSnapshot(data: string): Promise<void> {
  const n = getNative();
  if (!n) return;
  await safe(() => n.syncPrefs({ data }), undefined as unknown as void);
}

export async function pullNativePrefs(): Promise<Record<string, [string, unknown]>> {
  const n = getNative();
  if (!n) return {};
  return safe(() => n.pullPrefs(), {});
}

export async function requestNotificationsPermission(): Promise<void> {
  const n = getNative();
  if (!n) return;
  await safe(() => n.requestNotificationsPermission(), undefined as unknown as void);
}

export async function getLastLocation(): Promise<{ lat: number; lon: number } | null> {
  const n = getNative();
  if (n) {
    const r = await safe(() => n.getGeo(), null);
    if (r) return r;
  }
  return new Promise((resolve) => {
    if (!navigator.geolocation) {
      resolve(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lat: pos.coords.latitude, lon: pos.coords.longitude }),
      () => resolve(null),
      { timeout: 8000, maximumAge: 60000 },
    );
  });
}
