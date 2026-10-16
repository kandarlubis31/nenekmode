/**
 * Padanan SharedPreferences (namespace "Nenek_Assistant") di web.
 * Tersimpan sebagai JSON tunggal di localStorage.
 */

const KEY = "Nenek_Assistant";

type PrefsMap = Record<string, unknown>;

let cache: PrefsMap | null = null;
const persistListeners: Array<() => void> = [];

/** Daftarkan listener yang dipanggil tiap prefs berubah (dipakai sync native). */
export function addPersistListener(cb: () => void): void {
  persistListeners.push(cb);
}

/** Salinan mentah semua prefs (buat snapshot ke native). */
export function getRawMap(): PrefsMap {
  return { ...load() };
}

function load(): PrefsMap {
  if (cache === null) {
    try {
      const raw = localStorage.getItem(KEY);
      cache = raw ? (JSON.parse(raw) as PrefsMap) : {};
    } catch {
      cache = {};
    }
  }
  return cache;
}

function persist(): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(cache ?? {}));
  } catch {
    /* storage penuh/blokir: abaikan, jangan crash UI */
  }
  for (const cb of persistListeners) {
    try {
      cb();
    } catch {
      /* listener rusak: abaikan */
    }
  }
}

export const prefs = {
  getString(key: string, def = ""): string {
    const v = load()[key];
    return typeof v === "string" ? v : def;
  },
  getBoolean(key: string, def = false): boolean {
    const v = load()[key];
    return typeof v === "boolean" ? v : def;
  },
  getInt(key: string, def = 0): number {
    const v = load()[key];
    return typeof v === "number" && Number.isFinite(v) ? v : def;
  },
  /** Angka bebas (termasuk pecahan, mis. lat/lon). */
  getNumber(key: string, def = 0): number {
    const v = load()[key];
    if (typeof v === "number" && Number.isFinite(v)) return v;
    if (typeof v === "string") {
      const n = parseFloat(v);
      if (!Number.isNaN(n)) return n;
    }
    return def;
  },
  getJson<T>(key: string, def: T): T {
    const v = load()[key];
    if (typeof v === "string") {
      try {
        return JSON.parse(v) as T;
      } catch {
        return def;
      }
    }
    return def;
  },
  setString(key: string, value: string): void {
    load()[key] = value;
    persist();
  },
  setBoolean(key: string, value: boolean): void {
    load()[key] = value;
    persist();
  },
  setInt(key: string, value: number): void {
    load()[key] = value;
    persist();
  },
  setNumber(key: string, value: number): void {
    load()[key] = value;
    persist();
  },
  /** Simpan objek sebagai JSON string (mirip putString+JSONObject di Java). */
  setJson(key: string, value: unknown): void {
    load()[key] = JSON.stringify(value);
    persist();
  },
  remove(key: string): void {
    delete load()[key];
    persist();
  },
};
