/**
 * Sinkronisasi prefs antara localStorage (JS) dan SharedPreferences (native).
 *
 * - JS = sumber kebenaran settings; tiap perubahan di-push ke native (debounce).
 * - Native = sumber kebenaran state runtime yang ditulis receiver/service
 *   (mis. med_confirm_waiting, escalation_armed, med_streak) — di-pull + merge
 *   saat app dibuka / kembali ke foreground.
 */

import { prefs, getRawMap, addPersistListener } from "../lib/prefs";
import * as bridge from "./bridge";

/** Key yang ditulis mesin native (receiver/service) — native menang. */
const NATIVE_OWNED = [
  "med_confirm_waiting",
  "quiz_waiting",
  "water_confirm_waiting",
  "escalation_armed",
  "escalation_ts",
  "last_escalation",
  "med_streak",
  "med_streak_date",
  "pending_followup",
  "sim_warning_shown",
  "battery_ignore_requested",
];

function typedSnapshot(): string {
  const raw = getRawMap();
  const out: Record<string, [string, unknown]> = {};
  for (const [k, v] of Object.entries(raw)) {
    if (typeof v === "boolean") out[k] = ["b", v];
    else if (typeof v === "number") {
      if (Number.isInteger(v) && Math.abs(v) <= 2147483647) out[k] = ["i", v];
      else if (Number.isInteger(v)) out[k] = ["l", v];
      else out[k] = ["f", v];
    } else out[k] = ["s", String(v)];
  }
  return JSON.stringify(out);
}

function applyNativeValue(key: string, pair: [string, unknown]): void {
  const [type, value] = pair;
  switch (type) {
    case "b":
      prefs.setBoolean(key, Boolean(value));
      break;
    case "i":
    case "l":
    case "f":
      prefs.setNumber(key, Number(value));
      break;
    default:
      prefs.setString(key, String(value));
      break;
  }
}

/** Push seluruh snapshot settings ke native. */
export async function syncNativePrefs(): Promise<void> {
  if (!bridge.hasNativeBridge()) return;
  await bridge.syncPrefsSnapshot(typedSnapshot());
}

/** Pull state runtime dari native, merge ke localStorage, lalu push balik. */
export async function pullAndMergeNative(): Promise<void> {
  if (!bridge.hasNativeBridge()) return;
  let nativeAll: Record<string, [string, unknown]> = {};
  try {
    nativeAll = await bridge.pullNativePrefs();
  } catch {
    return;
  }

  for (const key of NATIVE_OWNED) {
    const e = nativeAll[key];
    if (e) applyNativeValue(key, e);
  }

  // chat_history: gabung baris (native service juga menulis riwayat)
  const nh = nativeAll["chat_history"];
  if (nh && typeof nh[1] === "string" && nh[1].length > 0) {
    const local = prefs.getString("chat_history", "");
    const localLines = new Set(local.split("\n").filter(Boolean));
    const extra = nh[1]
      .split("\n")
      .filter((l) => l.trim().length > 0 && !localLines.has(l));
    if (extra.length > 0) {
      prefs.setString("chat_history", local ? local + "\n" + extra.join("\n") : extra.join("\n"));
    }
  }

  // push balik settings terbaru (biar native ikut state JS)
  await syncNativePrefs();
}

// push otomatis (debounce) tiap prefs berubah
let syncTimer: number | null = null;
addPersistListener(() => {
  if (!bridge.hasNativeBridge()) return;
  if (syncTimer !== null) window.clearTimeout(syncTimer);
  syncTimer = window.setTimeout(() => {
    syncTimer = null;
    void syncNativePrefs();
  }, 400);
});
