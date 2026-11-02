import { useCallback, useEffect, useState } from "react";
import { prefs } from "../lib/prefs";
import { getMedTimes } from "../lib/actions";
import { nextPrayerShort } from "../lib/prayer";
import { weather } from "../lib/weather";
import * as bridge from "../native/bridge";

interface Props {
  onExit: () => void;
}

const DAYS_ID = ["Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu"];
const MONTHS_ID = [
  "Januari", "Februari", "Maret", "April", "Mei", "Juni",
  "Juli", "Agustus", "September", "Oktober", "November", "Desember",
];

function pad2(n: number): string {
  return n < 10 ? "0" + n : String(n);
}

function nextMedicineText(): string {
  const times = getMedTimes();
  const now = new Date();
  const nowMin = now.getHours() * 60 + now.getMinutes();
  let best = -1;
  let bestDiff = Number.MAX_SAFE_INTEGER;
  for (const t of times) {
    if (t.en === false) continue;
    const m = t.h * 60 + t.m;
    const diff = m >= nowMin ? m - nowMin : m + 24 * 60 - nowMin;
    if (diff < bestDiff) {
      bestDiff = diff;
      best = m;
    }
  }
  if (best < 0) return "Obat berikutnya: belum diatur";
  if (bestDiff === 0) return "Sekarang waktunya minum obat!";
  const when =
    bestDiff >= 60
      ? `${Math.floor(bestDiff / 60)} jam ${bestDiff % 60} menit`
      : `${bestDiff} menit`;
  return `Obat berikutnya ${pad2(Math.floor(best / 60))}:${pad2(best % 60)} (${when} lagi)`;
}

export default function BoardScreen({ onExit }: Props) {
  const [now, setNow] = useState(new Date());
  const [weatherText, setWeatherText] = useState("Cuaca: memuat...");
  const [battery, setBattery] = useState(-1);
  const [companionOn, setCompanionOn] = useState(prefs.getBoolean("keep_alive", false));
  const [pinned, setPinned] = useState(prefs.getBoolean("board_mode", false));

  const loadWeather = useCallback(() => {
    const lat = prefs.getNumber("last_lat", -6.2);
    const lon = prefs.getNumber("last_lon", 106.8);
    void weather
      .getCurrentWeather(lat, lon)
      .then(setWeatherText)
      .catch(() => setWeatherText("Cuaca: tidak tersedia (cek internet)"));
  }, []);

  useEffect(() => {
    // jam harus berganti tepat di menit baru, bukan tiap 30 detik
    const clock = window.setInterval(() => setNow(new Date()), 1000);
    // status lambat (baterai/Teman) cukup tiap 30 detik — dulu baterai hanya
    // dibaca sekali saat layar dibuka, jadi tampilannya bisa basi
    const slow = window.setInterval(() => {
      setCompanionOn(prefs.getBoolean("keep_alive", false));
      void bridge.getBatteryLevel().then(setBattery);
    }, 30000);
    loadWeather();
    void bridge.getBatteryLevel().then(setBattery);

    // layar tetap nyala selama papan info (APK: native wake lock)
    let wakeLock: { release: () => Promise<void> } | null = null;
    void (navigator as unknown as {
      wakeLock?: { request: (t: "screen") => Promise<{ release: () => Promise<void> }> };
    }).wakeLock
      ?.request("screen")
      .then((lock) => {
        wakeLock = lock;
      })
      .catch(() => undefined);

    return () => {
      window.clearInterval(clock);
      window.clearInterval(slow);
      void wakeLock?.release().catch(() => undefined);
    };
  }, [loadWeather]);

  const exit = () => {
    prefs.setBoolean("board_mode", false);
    onExit();
  };

  // ala BoardActivity Java: bisa dijadikan mode permanen (buka langsung ke papan)
  const togglePin = () => {
    const next = !prefs.getBoolean("board_mode", false);
    prefs.setBoolean("board_mode", next);
    setPinned(next);
  };

  return (
    <div className="board">
      <div className="clock">
        {pad2(now.getHours())}:{pad2(now.getMinutes())}
      </div>
      <div className="date">
        {DAYS_ID[now.getDay()]}, {now.getDate()} {MONTHS_ID[now.getMonth()]} {now.getFullYear()}
      </div>
      <div className="info">{nextPrayerShort()}</div>
      <div className="info">{nextMedicineText()}</div>
      <div className="info" style={{ cursor: "pointer" }} onClick={loadWeather}>
        {weatherText}
      </div>
      <div className="info">Mode Teman: {companionOn ? "AKTIF ✅" : "MATI ❌"}</div>
      <div className="info">{battery >= 0 ? `Baterai ${battery}%` : "Baterai: -"}</div>
      <button className="hint" onClick={togglePin}>
        {pinned
          ? "📌 Mode Papan: AKTIF — app langsung buka papan info (ketuk buat matikan)"
          : "📌 Jadikan Mode Papan (app langsung buka sini)"}
      </button>
      <button className="hint" onClick={exit}>
        (ketuk untuk kembali ke layar utama)
      </button>
    </div>
  );
}
