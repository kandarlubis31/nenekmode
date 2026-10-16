/**
 * Riwayat tekanan darah (opsional): "tekanan darahku 140/90" -> tersimpan.
 * Padanan langsung BloodPressureStore.java. Maks 30 entry terakhir.
 */

import { prefs } from "./prefs";

const KEY = "bp_history";
const MAX_ENTRIES = 30;

export interface BpEntry {
  date: string; // "17 Sep 14:30"
  sys: number;
  dia: number;
}

const MONTHS = [
  "Jan", "Feb", "Mar", "Apr", "Mei", "Jun",
  "Jul", "Agu", "Sep", "Okt", "Nov", "Des",
];

function pad2(n: number): string {
  return n < 10 ? "0" + n : String(n);
}

function formatDate(d: Date): string {
  return (
    pad2(d.getDate()) +
    " " + MONTHS[d.getMonth()] +
    " " + pad2(d.getHours()) + ":" + pad2(d.getMinutes())
  );
}

export const bp = {
  /** @returns false jika format tidak valid. */
  add(sys: number, dia: number): boolean {
    if (sys < 50 || sys > 260 || dia < 30 || dia > 180 || dia >= sys) return false;
    try {
      const arr = bp.getAll();
      const e: BpEntry = { date: formatDate(new Date()), sys, dia };
      const out: BpEntry[] = [e];
      for (let i = 0; i < arr.length && out.length < MAX_ENTRIES; i++) {
        out.push(arr[i]);
      }
      prefs.setString(KEY, JSON.stringify(out));
      return true;
    } catch {
      return false;
    }
  },

  getAll(): BpEntry[] {
    const out: BpEntry[] = [];
    try {
      const arr = JSON.parse(prefs.getString(KEY, "[]")) as BpEntry[];
      for (const o of arr) {
        if (typeof o?.sys === "number" && o.sys > 0) {
          out.push({ date: o.date ?? "", sys: o.sys, dia: o.dia ?? 0 });
        }
      }
    } catch {
      /* riwayat rusak: anggap kosong */
    }
    return out;
  },

  latest(): BpEntry | null {
    const all = bp.getAll();
    return all.length > 0 ? all[0] : null;
  },

  count(): number {
    return bp.getAll().length;
  },

  clear(): void {
    prefs.remove(KEY);
  },

  /**
   * Kalimat riwayat singkat buat dibacakan:
   * "Riwayat sebelumnya: 128 atas 82, dan 130 atas 85." (3 terakhir setelah yang terbaru)
   */
  speechHistory(): string {
    const all = bp.getAll();
    if (all.length <= 1) return "";
    const parts: string[] = [];
    const n = Math.min(3, all.length - 1);
    for (let i = 1; i <= n; i++) {
      const e = all[i];
      let s = `${e.sys} atas ${e.dia}`;
      if (e.date) s += ` (${e.date})`;
      parts.push(s);
    }
    return "Riwayat sebelumnya: " + parts.join(", ") + ".";
  },

  /** Kalimat respon suara setelah nyatat. */
  speechAfterAdd(elderName: string, sys: number, dia: number): string {
    const base = `Oke, tekanan darah ${sys} atas ${dia} bawah sudah dicatat ya ${elderName}.`;
    if (sys >= 180 || dia >= 120) {
      return base + " Wah, ini tinggi sekali. Sebaiknya hubungi dokter sekarang ya.";
    }
    if (sys >= 140 || dia >= 90) {
      return base + " Ini termasuk tinggi. Kurangi asin-asinan, istirahat yang cukup, dan kalau sering gitu kabari keluarga atau dokter ya.";
    }
    if (sys >= 130 || dia >= 85) {
      return base + " Agak tinggi sedikit. Jaga makan dan istirahat ya.";
    }
    return base + " Alhamdulillah, tekanannya bagus. Pertahankan ya.";
  },

  /** Kalimat jawaban "berapa tekanan darahku?". */
  speechLast(elderName: string): string {
    const e = bp.latest();
    if (!e) {
      return `Belum ada catatan tekanan darah ${elderName}. Kalau sudah diukur, bilang aja, misalnya tekanan darahku 130 atas 85.`;
    }
    return (
      `Catatan terakhir, tanggal ${e.date}, tekanan darah ${e.sys} atas ${e.dia}.` +
      (e.sys >= 140 || e.dia >= 90
        ? " Itu agak tinggi ya, jangan lupa kontrol ke dokter."
        : " Alhamdulillah masih bagus.")
    );
  },
};
