/**
 * Penjadwalan alarm dari JS — port AlarmScheduler-pemakaian di
 * ReminderReceiver/CheckInReceiver/WaterReceiver/BirthdayReceiver/
 * ProactiveReceiver + reschedule ala BootReceiver & onCreate MainActivity.
 *
 * Di APK: diteruskan ke plugin native (AlarmManager exact).
 * Di browser: fallback setTimeout lewat bridge.
 */

import { prefs } from "./prefs";
import * as bridge from "../native/bridge";
import * as prayer from "./prayer";
import { getMedTimes } from "./actions";
import { rearmIfArmed } from "./escalation";

export const CODES = {
  checkin: 1,
  reminderBase: 100, // 100+index
  snooze: 500,
  confirmBase: 900, // 900+index
  proactive: 700,
  prayerBase: 1100, // 1100+index
  prayerRefresh: 1150,
  water: 1200,
  birthday: 1300,
  escalation: 1400,
  watchdog: 1500,
} as const;

function nextOccurrence(hour: number, minute: number): number {
  const cal = new Date();
  cal.setHours(hour, minute, 0, 0);
  if (cal.getTime() <= Date.now()) cal.setDate(cal.getDate() + 1);
  return cal.getTime();
}

// ================= OBAT =================

export function scheduleSlot(index: number, hour: number, minute: number): void {
  void bridge.scheduleAlarm({
    code: CODES.reminderBase + index,
    epochMs: nextOccurrence(hour, minute),
    kind: "reminder",
    index,
  });
}

/** Jadwalkan semua slot obat yang aktif ke kejadian terdekat berikutnya. */
export function setReminders(): void {
  const times = getMedTimes();
  times.forEach((t, i) => {
    if (t.en === false) return;
    scheduleSlot(i, t.h, t.m);
  });
}

export function cancelReminders(): void {
  const n = Math.max(getMedTimes().length, 3);
  for (let i = 0; i < n; i++) {
    void bridge.cancelAlarm(CODES.reminderBase + i);
    void bridge.cancelAlarm(CODES.confirmBase + i);
  }
  void bridge.cancelAlarm(CODES.snooze);
}

/** Tunda 10 menit (dipakai saat nenek bilang "belum"). */
export function snoozeTenMinutes(): void {
  void bridge.scheduleAlarm({
    code: CODES.snooze,
    epochMs: Date.now() + 10 * 60 * 1000,
    kind: "reminder_snooze",
    index: 0,
  });
}

// ================= CEK HARIAN =================

export function setCheckIn(hour: number, minute: number): void {
  void bridge.scheduleAlarm({
    code: CODES.checkin,
    epochMs: nextOccurrence(hour, minute),
    kind: "checkin",
  });
}

export function cancelCheckIn(): void {
  void bridge.cancelAlarm(CODES.checkin);
}

// ================= AIR / ULANG TAHUN / PROAKTIF =================

export function scheduleWaterNext(): void {
  if (!prefs.getBoolean("water_enabled", false)) return;
  const intervalMs = prefs.getInt("water_interval_min", 120) * 60 * 1000;
  void bridge.scheduleAlarm({
    code: CODES.water,
    epochMs: Date.now() + intervalMs,
    kind: "water",
  });
}

export function cancelWater(): void {
  void bridge.cancelAlarm(CODES.water);
}

export function scheduleBirthdayNext(): void {
  if (!prefs.getBoolean("birthday_enabled", false)) return;
  const cal = new Date();
  cal.setDate(cal.getDate() + 1);
  cal.setHours(0, 5, 0, 0);
  void bridge.scheduleAlarm({
    code: CODES.birthday,
    epochMs: cal.getTime(),
    kind: "birthday",
  });
}

export function cancelBirthday(): void {
  void bridge.cancelAlarm(CODES.birthday);
}

interface ProactiveSlot {
  h: number;
  m: number;
}

export function proactiveRescheduleNext(): void {
  try {
    const raw = prefs.getString(
      "proactive_times",
      '[{"h":9,"m":30},{"h":15,"m":30}]',
    );
    const times = JSON.parse(raw) as ProactiveSlot[];
    let best = -1;
    for (const t of times) {
      const cal = new Date();
      cal.setHours(t.h, t.m, 0, 0);
      if (cal.getTime() <= Date.now()) cal.setDate(cal.getDate() + 1);
      if (best < 0 || cal.getTime() < best) best = cal.getTime();
    }
    if (best > 0) {
      void bridge.scheduleAlarm({
        code: CODES.proactive,
        epochMs: best,
        kind: "proactive",
      });
    }
  } catch {
    /* abaikan */
  }
}

// ================= RESCHEDULE KESELURUHAN (alau app dibuka / boot) =================

export function rescheduleAllOnStart(): void {
  if (prefs.getBoolean("reminder_enabled", false)) setReminders();
  if (prefs.getBoolean("checkin_enabled", false)) {
    setCheckIn(prefs.getInt("checkin_hour", 9), prefs.getInt("checkin_minute", 0));
  }
  if (prayer.isEnabled()) prayer.refreshIfNeededAsync();
  if (prefs.getBoolean("water_enabled", false)) scheduleWaterNext();
  if (prefs.getBoolean("birthday_enabled", false)) scheduleBirthdayNext();
  if (prefs.getBoolean("companion_enabled", false)) proactiveRescheduleNext();
  rearmIfArmed();
}
