import { useState } from "react";
import { prefs } from "../lib/prefs";
import { getMedTimes } from "../lib/actions";
import { testApiKey, resolveApiKey } from "../lib/groq";
import {
  cancelReminders,
  cancelBirthday,
  cancelWater,
  proactiveRescheduleNext,
  scheduleBirthdayNext,
  scheduleWaterNext,
} from "../lib/scheduling";
import { applyCheckInSetting, applyReminderSetting } from "../lib/receivers";
import * as prayer from "../lib/prayer";
import * as bridge from "../native/bridge";

interface Props {
  onBack: () => void;
}

const MODEL_IDS = ["openai/gpt-oss-20b", "openai/gpt-oss-120b"];
const MODEL_LABELS = ["Cepat (GPT-OSS 20B)", "Pintar (GPT-OSS 120B)"];

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}

function parseTimeInt(s: string, fallback: number): number {
  const t = s.trim();
  if (!t) return fallback;
  const n = parseInt(t, 10);
  return Number.isNaN(n) ? fallback : n;
}

function isValidPhoneNumber(phone: string): boolean {
  const p = phone.replace(/[^0-9+]/g, "");
  return (p.startsWith("0") || p.startsWith("+62")) && p.length >= 10;
}

export default function SettingsScreen({ onBack }: Props) {
  const [tab, setTab] = useState<"umum" | "teknis">("umum");
  const [toast, setToast] = useState("");

  // ---- Umum ----
  const [nenekName, setNenekName] = useState(prefs.getString("nenek_name", "Bu"));
  const [assistantName, setAssistantName] = useState(prefs.getString("assistant_name", "Kandar"));
  const [emergencyNumber, setEmergencyNumber] = useState(prefs.getString("emergency_number", ""));
  const [favName, setFavName] = useState(prefs.getString("favorite_contact_name", ""));
  const [favNumber, setFavNumber] = useState(prefs.getString("favorite_contact_number", ""));
  const [fontSize, setFontSize] = useState(prefs.getInt("font_size", 1));
  const [highContrast, setHighContrast] = useState(prefs.getBoolean("high_contrast", false));

  const initialMed = getMedTimes();
  const medSlot = (i: number) => initialMed[i] ?? { h: i === 0 ? 8 : i === 1 ? 14 : 20, m: 0, en: false };
  const [medH, setMedH] = useState([0, 1, 2].map((i) => String(medSlot(i).h)));
  const [medM, setMedM] = useState([0, 1, 2].map((i) => String(medSlot(i).m)));
  const [medEn, setMedEn] = useState([0, 1, 2].map((i) => medSlot(i).en !== false));
  const [reminderEnabled, setReminderEnabled] = useState(prefs.getBoolean("reminder_enabled", false));
  const [reminderMessage, setReminderMessage] = useState(
    prefs.getString("reminder_message", "Waktu minum obat ya!"),
  );

  const [checkinHour, setCheckinHour] = useState(String(prefs.getInt("checkin_hour", 9)));
  const [checkinMin, setCheckinMin] = useState(String(prefs.getInt("checkin_minute", 0)));
  const [checkinEnabled, setCheckinEnabled] = useState(prefs.getBoolean("checkin_enabled", false));
  const [escalationEnabled, setEscalationEnabled] = useState(prefs.getBoolean("escalation_enabled", true));

  // ---- Teknis ----
  const [keepAlive, setKeepAlive] = useState(prefs.getBoolean("keep_alive", false));
  const [proactive, setProactive] = useState(prefs.getBoolean("companion_enabled", true));
  const [wakeMode, setWakeMode] = useState(prefs.getString("wake_mode", "off") === "on");
  const [newsEnabled, setNewsEnabled] = useState(prefs.getBoolean("news_enabled", true));
  const [adzanEnabled, setAdzanEnabled] = useState(prefs.getBoolean("adzan_enabled", true));

  const [byokEnabled, setByokEnabled] = useState(prefs.getBoolean("byok_enabled", false));
  const [byokKey, setByokKey] = useState(prefs.getString("byok_api_key", ""));
  const [aiModel, setAiModel] = useState(prefs.getString("ai_model", MODEL_IDS[0]));

  const [voskEnabled, setVoskEnabled] = useState(prefs.getBoolean("vosk_enabled", false));
  const [voskUrl, setVoskUrl] = useState(prefs.getString("vosk_model_url", ""));

  const [waterEnabled, setWaterEnabled] = useState(prefs.getBoolean("water_enabled", false));
  const [waterInterval, setWaterInterval] = useState(String(prefs.getInt("water_interval_min", 120)));
  const [storiesEnabled, setStoriesEnabled] = useState(prefs.getBoolean("stories_enabled", false));
  const [weatherAlert, setWeatherAlert] = useState(prefs.getBoolean("weather_alert_enabled", false));
  const [bpEnabled, setBpEnabled] = useState(prefs.getBoolean("bp_enabled", false));
  const [birthdayEnabled, setBirthdayEnabled] = useState(prefs.getBoolean("birthday_enabled", false));
  const [birthdayList, setBirthdayList] = useState(
    (() => {
      try {
        return (JSON.parse(prefs.getString("birthday_list", "[]")) as string[]).join(", ");
      } catch {
        return "";
      }
    })(),
  );
  const [nightEnabled, setNightEnabled] = useState(prefs.getBoolean("night_mode_enabled", false));
  const [nightStart, setNightStart] = useState(String(prefs.getInt("night_start_hour", 21)));
  const [nightEnd, setNightEnd] = useState(String(prefs.getInt("night_end_hour", 5)));

  const showToast = (msg: string) => {
    setToast(msg);
    window.setTimeout(() => setToast(""), 3000);
  };

  const testKey = () => {
    let key = byokKey.trim();
    if (!key) key = resolveApiKey();
    showToast("Menguji koneksi...");
    void testApiKey(key, { onResult: (_ok, message) => showToast(message) });
  };

  const doBackup = () => {
    const data = localStorage.getItem("Nenek_Assistant") ?? "{}";
    const blob = new Blob([data], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `nenekmode-backup-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
    showToast("Backup tersimpan ke file");
  };

  const doRestore = () => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = "application/json";
    input.onchange = () => {
      const file = input.files?.[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        try {
          JSON.parse(String(reader.result)); // validasi dulu
          localStorage.setItem("Nenek_Assistant", String(reader.result));
          showToast("Restore berhasil! Muat ulang...");
          window.setTimeout(() => window.location.reload(), 1200);
        } catch {
          showToast("File backup tidak valid");
        }
      };
      reader.readAsText(file);
    };
    input.click();
  };

  const saveSettings = () => {
    if (!nenekName.trim() || !assistantName.trim()) {
      showToast("Nama tidak boleh kosong");
      return;
    }
    if (emergencyNumber.trim() && !isValidPhoneNumber(emergencyNumber.trim())) {
      showToast("Nomor telepon darurat tidak valid");
      return;
    }
    if (favNumber.trim() && !isValidPhoneNumber(favNumber.trim())) {
      showToast("Nomor kontak favorit tidak valid");
      return;
    }

    prefs.setString("nenek_name", nenekName.trim());
    prefs.setString("assistant_name", assistantName.trim());
    prefs.setString("emergency_number", emergencyNumber.trim());
    prefs.setString("favorite_contact_name", favName.trim());
    prefs.setString("favorite_contact_number", favNumber.trim());
    prefs.setInt("font_size", fontSize);
    prefs.setBoolean("high_contrast", highContrast);
    prefs.setString("reminder_message", reminderMessage.trim());

    // jadwal obat multi-slot
    const times = [0, 1, 2].map((i) => ({
      h: clamp(parseTimeInt(medH[i], i === 0 ? 8 : i === 1 ? 14 : 20), 0, 23),
      m: clamp(parseTimeInt(medM[i], 0), 0, 59),
      en: medEn[i],
    }));
    prefs.setString("med_times", JSON.stringify(times));
    const anyEnabled = times.some((t) => t.en);
    prefs.setBoolean("reminder_enabled", anyEnabled || reminderEnabled);

    // check-in
    prefs.setInt("checkin_hour", clamp(parseTimeInt(checkinHour, 9), 0, 23));
    prefs.setInt("checkin_minute", clamp(parseTimeInt(checkinMin, 0), 0, 59));
    prefs.setBoolean("checkin_enabled", checkinEnabled);
    prefs.setBoolean("escalation_enabled", escalationEnabled);

    // mode teman
    prefs.setBoolean("keep_alive", keepAlive);
    prefs.setBoolean("companion_enabled", proactive);
    prefs.setString("wake_mode", wakeMode ? "on" : "off");
    prefs.setBoolean("news_enabled", newsEnabled);
    prefs.setBoolean("adzan_enabled", adzanEnabled);

    // fitur opsional
    prefs.setBoolean("water_enabled", waterEnabled);
    prefs.setInt("water_interval_min", clamp(parseTimeInt(waterInterval, 120), 15, 720));
    prefs.setBoolean("stories_enabled", storiesEnabled);
    prefs.setBoolean("weather_alert_enabled", weatherAlert);
    prefs.setBoolean("bp_enabled", bpEnabled);
    prefs.setBoolean("birthday_enabled", birthdayEnabled);
    const bdays = birthdayList
      .split(",")
      .map((d) => d.trim())
      .filter((s) => /^(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$/.test(s));
    prefs.setString("birthday_list", JSON.stringify(bdays));
    prefs.setBoolean("night_mode_enabled", nightEnabled);
    prefs.setInt("night_start_hour", clamp(parseTimeInt(nightStart, 21), 0, 23));
    prefs.setInt("night_end_hour", clamp(parseTimeInt(nightEnd, 5), 0, 23));

    // BYOK + model
    prefs.setBoolean("byok_enabled", byokEnabled);
    prefs.setString("byok_api_key", byokKey.trim());
    prefs.setBoolean("vosk_enabled", voskEnabled);
    prefs.setString("vosk_model_url", voskUrl.trim());
    prefs.setString("ai_model", aiModel);

    // terapkan semua alarm
    cancelReminders();
    applyReminderSetting(anyEnabled || reminderEnabled);
    applyCheckInSetting(
      checkinEnabled,
      clamp(parseTimeInt(checkinHour, 9), 0, 23),
      clamp(parseTimeInt(checkinMin, 0), 0, 59),
    );
    proactiveRescheduleNext();

    if (adzanEnabled) prayer.refreshIfNeededAsync();
    else prayer.cancelAll();

    if (waterEnabled) {
      cancelWater();
      scheduleWaterNext();
    } else cancelWater();

    if (birthdayEnabled) {
      cancelBirthday();
      scheduleBirthdayNext();
    } else cancelBirthday();

    // service keep-alive (Mode Teman)
    if (keepAlive) void bridge.startCompanion();
    else void bridge.stopCompanion();

    showToast("Pengaturan tersimpan!");
    window.setTimeout(onBack, 800);
  };

  const Switch = ({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) => (
    <div className="switch-row">
      <span>{label}</span>
      <input type="checkbox" className="switch" checked={value} onChange={(e) => onChange(e.target.checked)} />
    </div>
  );

  return (
    <div className="screen">
      <div className="settings-header">
        <button className="btn gray" style={{ flex: "none", minHeight: 46, padding: "8px 14px" }} onClick={onBack}>
          ← Kembali
        </button>
        <h1>Pengaturan</h1>
      </div>

      <div className="tabs">
        <button className={`tab ${tab === "umum" ? "active" : ""}`} onClick={() => setTab("umum")}>
          Umum
        </button>
        <button className={`tab ${tab === "teknis" ? "active" : ""}`} onClick={() => setTab("teknis")}>
          Mode Teman & AI
        </button>
      </div>

      {tab === "umum" && (
        <>
          <div className="category">
            <h2>👤 Identitas</h2>
            <div className="field">
              <label>Nama Nenek (atau istilah panggilan)</label>
              <input type="text" value={nenekName} onChange={(e) => setNenekName(e.target.value)} />
              <div className="hint">Ini akan dipanggil oleh asisten saat berbicara</div>
            </div>
            <div className="field">
              <label>Nama Asisten</label>
              <input type="text" value={assistantName} onChange={(e) => setAssistantName(e.target.value)} />
            </div>
            <div className="field">
              <label>Nomor Telepon Darurat</label>
              <input type="tel" value={emergencyNumber} onChange={(e) => setEmergencyNumber(e.target.value)} />
              <div className="hint">PENTING: Nomor yang akan dihubungi saat ada keadaan darurat.</div>
            </div>
            <div className="field">
              <label>Kontak Favorit — Nama</label>
              <input type="text" value={favName} onChange={(e) => setFavName(e.target.value)} />
            </div>
            <div className="field">
              <label>Kontak Favorit — Nomor</label>
              <input type="tel" value={favNumber} onChange={(e) => setFavNumber(e.target.value)} />
              <div className="hint">Bilang 'Telpon anak' atau 'Nelpon cucu' untuk langsung menelpon.</div>
            </div>
          </div>

          <div className="category">
            <h2>♿ Aksesibilitas</h2>
            <div className="field">
              <label>Ukuran Font</label>
              <select value={fontSize} onChange={(e) => setFontSize(Number(e.target.value))}>
                <option value={1}>Normal</option>
                <option value={2}>Besar</option>
                <option value={3}>Sangat Besar</option>
              </select>
            </div>
            <Switch label="Mode Kontras Tinggi" value={highContrast} onChange={setHighContrast} />
          </div>

          <div className="category">
            <h2>💊 Pengingat Obat</h2>
            <div className="note">Jadwal minum obat (maks 3 waktu per hari)</div>
            {[0, 1, 2].map((i) => (
              <div className="med-slot" key={i}>
                <input
                  type="number"
                  min={0}
                  max={23}
                  value={medH[i]}
                  onChange={(e) => setMedH((prev) => prev.map((v, j) => (j === i ? e.target.value : v)))}
                />
                <b>:</b>
                <input
                  type="number"
                  min={0}
                  max={59}
                  value={medM[i]}
                  onChange={(e) => setMedM((prev) => prev.map((v, j) => (j === i ? e.target.value : v)))}
                />
                <input
                  type="checkbox"
                  className="switch"
                  checked={medEn[i]}
                  onChange={(e) => setMedEn((prev) => prev.map((v, j) => (j === i ? e.target.checked : v)))}
                />
              </div>
            ))}
            <div className="field">
              <label>Pesan pengingat</label>
              <input type="text" value={reminderMessage} onChange={(e) => setReminderMessage(e.target.value)} />
            </div>
            <Switch label="Aktifkan pengingat" value={reminderEnabled} onChange={setReminderEnabled} />
          </div>

          <div className="category">
            <h2>⏰ Cek Harian</h2>
            <div className="grid2">
              <div className="field">
                <label>Jam</label>
                <input type="number" min={0} max={23} value={checkinHour} onChange={(e) => setCheckinHour(e.target.value)} />
              </div>
              <div className="field">
                <label>Menit</label>
                <input type="number" min={0} max={59} value={checkinMin} onChange={(e) => setCheckinMin(e.target.value)} />
              </div>
            </div>
            <Switch label="Aktifkan cek harian" value={checkinEnabled} onChange={setCheckinEnabled} />
            <Switch
              label="SMS keluarga bila tak ada respons 30 menit"
              value={escalationEnabled}
              onChange={setEscalationEnabled}
            />
          </div>

          <div className="category">
            <h2>💾 Backup & Restore</h2>
            <div className="note">
              Simpan semua pengaturan & memori ke file saat ganti HP, lalu pulihkan di HP baru.
            </div>
            <div className="btn-row">
              <button className="btn" onClick={doBackup}>Backup ke File</button>
              <button className="btn gray" onClick={doRestore}>Restore dari File</button>
            </div>
          </div>
        </>
      )}

      {tab === "teknis" && (
        <>
          <div className="category">
            <h2>🗣️ Mode Teman</h2>
            <Switch label="Dengar terus walau layar mati" value={keepAlive} onChange={setKeepAlive} />
            <Switch label="Sapaan proaktif (nyapa duluan)" value={proactive} onChange={setProactive} />
            <Switch label="Harus panggil nama asisten dulu" value={wakeMode} onChange={setWakeMode} />
            <Switch label="Berita pagi" value={newsEnabled} onChange={setNewsEnabled} />
            <Switch label="Pengingat adzan 5 waktu" value={adzanEnabled} onChange={setAdzanEnabled} />
          </div>

          <div className="category">
            <h2>🤖 AI & Kunci API (BYOK)</h2>
            <Switch label="Pakai API key sendiri (BYOK)" value={byokEnabled} onChange={setByokEnabled} />
            <div className="field">
              <label>API Key</label>
              <input
                type="text"
                value={byokKey}
                onChange={(e) => setByokKey(e.target.value)}
                placeholder="gsk_..."
              />
              <div className="hint">
                Ambil gratis di console.groq.com/keys. Kalau kosong, dipakai kunci bawaan aplikasi.
              </div>
            </div>
            <div className="field">
              <label>Model AI</label>
              <select value={aiModel} onChange={(e) => setAiModel(e.target.value)}>
                {MODEL_IDS.map((id, i) => (
                  <option key={id} value={id}>{MODEL_LABELS[i]}</option>
                ))}
              </select>
            </div>
            <button className="btn" onClick={testKey}>Tes Koneksi AI</button>
          </div>

          <div className="category">
            <h2>📴 Panggilan Offline (Wake Word)</h2>
            <Switch label="Harus dipanggil namanya dulu (offline)" value={voskEnabled} onChange={setVoskEnabled} />
            <div className="field">
              <label>URL Model Suara (.zip Vosk)</label>
              <input type="text" value={voskUrl} onChange={(e) => setVoskUrl(e.target.value)} />
              <div className="hint">Model (40-60 MB) diunduh otomatis saat Mode Teman aktif di APK.</div>
            </div>
          </div>

          <div className="category">
            <h2>✨ Fitur Tambahan (Opsional)</h2>
            <Switch label="Pengingat minum air" value={waterEnabled} onChange={setWaterEnabled} />
            <div className="field">
              <label>Interval minum air (menit)</label>
              <input type="number" value={waterInterval} onChange={(e) => setWaterInterval(e.target.value)} />
            </div>
            <Switch label="Cerita & nasihat ('ceritain dongeng')" value={storiesEnabled} onChange={setStoriesEnabled} />
            <Switch label="Peringatan hujan tiap pagi" value={weatherAlert} onChange={setWeatherAlert} />
            <Switch label="Catat tekanan darah lewat suara" value={bpEnabled} onChange={setBpEnabled} />
            <Switch label="Sapaan ulang tahun / hari spesial" value={birthdayEnabled} onChange={setBirthdayEnabled} />
            <div className="field">
              <label>Tanggal spesial (MM-dd, pisahkan koma)</label>
              <input
                type="text"
                value={birthdayList}
                onChange={(e) => setBirthdayList(e.target.value)}
                placeholder="12-25, 01-01"
              />
            </div>
            <Switch label="Mode malam (jangan ganggu saat tidur)" value={nightEnabled} onChange={setNightEnabled} />
            <div className="grid2">
              <div className="field">
                <label>Mulai (jam)</label>
                <input type="number" min={0} max={23} value={nightStart} onChange={(e) => setNightStart(e.target.value)} />
              </div>
              <div className="field">
                <label>Selesai (jam)</label>
                <input type="number" min={0} max={23} value={nightEnd} onChange={(e) => setNightEnd(e.target.value)} />
              </div>
            </div>
          </div>
        </>
      )}

      <button className="btn" style={{ minHeight: 60 }} onClick={saveSettings}>
        💾 Simpan Pengaturan
      </button>

      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}
