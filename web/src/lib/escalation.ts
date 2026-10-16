/**
 * Escalation check-in (jaring pengaman) — port EscalationManager.java:
 * - CheckInReceiver menyapa -> arm() + alarm 30 menit (requestCode 1400)
 * - Ada tanda hidup (bicara/jawab) -> cancelEscalation()
 * - Alarm mati tanpa respons -> trigger(): SMS otomatis ke keluarga
 */

import { prefs } from "./prefs";
import * as bridge from "../native/bridge";

export const ESCALATION_CODE = 1400;
export const DELAY_MS = 30 * 60 * 1000;

/** Dipanggil CheckInReceiver: mulai memantau, alarm 30 menit ke depan. */
export function arm(): void {
  if (!prefs.getBoolean("escalation_enabled", true)) return;

  prefs.setBoolean("escalation_armed", true);
  prefs.setInt("escalation_ts", Date.now());

  void bridge.scheduleAlarm({
    code: ESCALATION_CODE,
    epochMs: Date.now() + DELAY_MS,
    kind: "escalation",
  });
}

/** Dipanggil begitu ada tanda hidup (lansia bicara / jawaban cek). */
export function cancelEscalation(): void {
  if (!prefs.getBoolean("escalation_armed", false)) return;
  prefs.setBoolean("escalation_armed", false);
  void bridge.cancelAlarm(ESCALATION_CODE);
}

/** Dipanggil EscalationReceiver: tidak ada respons -> kirim SMS ke keluarga. */
export async function trigger(): Promise<void> {
  if (!prefs.getBoolean("escalation_armed", false)) return;
  prefs.setBoolean("escalation_armed", false); // sekali per sesi cek

  const number = prefs.getString("emergency_number", "").trim();
  if (!number || number.includes("XXXXXXXXXX")) return;

  const elderName = prefs.getString("nenek_name", "Bu");
  const d = new Date();
  const time = `${d.getHours() < 10 ? "0" : ""}${d.getHours()}:${d.getMinutes() < 10 ? "0" : ""}${d.getMinutes()}`;
  const sms =
    `Laporan ${time}: ${elderName} tidak merespons cek harian (30 menit). ` +
    "Mohon segera hubungi beliau.";

  const ok = await bridge.sendSms(number, sms);
  if (ok) prefs.setString("last_escalation", sms);
}

/** Pasang ulang alarm escalation setelah app dibuka/reboot bila masih armed. */
export function rearmIfArmed(): void {
  if (!prefs.getBoolean("escalation_armed", false)) return;
  const ts = prefs.getInt("escalation_ts", 0);
  const remaining = ts + DELAY_MS - Date.now();
  if (remaining <= 0) {
    // sudah lewat tapi belum trigger (HP mati) -> langsung eskalasi
    void trigger();
  } else {
    void bridge.scheduleAlarm({
      code: ESCALATION_CODE,
      epochMs: Date.now() + remaining,
      kind: "escalation",
    });
  }
}
