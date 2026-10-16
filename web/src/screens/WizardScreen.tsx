import { useState } from "react";
import { prefs } from "../lib/prefs";
import { setReminders } from "../lib/scheduling";
import * as bridge from "../native/bridge";

interface Props {
  onFinish: () => void;
}

const TITLES = [
  "Selamat Datang! 👋",
  "Nomor Darurat 🚨",
  "Jadwal Minum Obat 💊",
  "Semua Siap! ✅",
];

const SUBTITLES = [
  "Isi nama panggilan dan nama asistennya ya.",
  "Nomor yang dihubungi saat darurat. Bisa dilewati dulu.",
  "Kapan waktu minum obat? Contoh: 08:00. Bisa dilewati dulu.",
  "Pengaturan awal sudah lengkap. Bisa diubah kapan saja di Pengaturan.",
];

const TIME_RE = /^([01]?[0-9]|2[0-3]):[0-5][0-9]$/;

export default function WizardScreen({ onFinish }: Props) {
  const [step, setStep] = useState(0);
  const [nenekName, setNenekName] = useState(prefs.getString("nenek_name", "Bu"));
  const [assistantName, setAssistantName] = useState(prefs.getString("assistant_name", "Kandar"));
  const [emergencyPhone, setEmergencyPhone] = useState(prefs.getString("emergency_number", ""));
  const [contactName, setContactName] = useState(prefs.getString("favorite_contact_name", ""));
  const [contactPhone, setContactPhone] = useState(prefs.getString("favorite_contact_number", ""));
  const [medOn, setMedOn] = useState(prefs.getBoolean("reminder_enabled", false));
  const [med1, setMed1] = useState("08:00");
  const [med2, setMed2] = useState("");
  const [med3, setMed3] = useState("");

  const [summary, setSummary] = useState("");

  const buzz = () => navigator.vibrate?.(20);

  const fillSummary = () => {
    const nenek = nenekName.trim();
    const asisten = assistantName.trim();
    const darurat = emergencyPhone.trim();
    let sb = `• Asisten: ${asisten || "Kandar"} menemani ${nenek || "Bu"}\n`;
    sb += `• Nomor darurat: ${darurat || "(belum diisi)"}\n`;
    if (medOn) {
      const times = [med1.trim(), med2.trim(), med3.trim()].filter((s) => TIME_RE.test(s));
      sb += `• Pengingat obat: ${times.length > 0 ? times.join(", ") : "aktif (jadwal default)"}`;
    } else {
      sb += "• Pengingat obat: nonaktif";
    }
    setSummary(sb);
  };

  const showStep = (s: number) => {
    if (s === 3) fillSummary();
    setStep(s);
  };

  const onNext = () => {
    buzz();
    if (step < 3) {
      showStep(step + 1);
      return;
    }
    saveAndFinish();
  };

  const saveAndFinish = () => {
    const nenek = nenekName.trim();
    const asisten = assistantName.trim();

    prefs.setString("nenek_name", nenek || "Bu");
    prefs.setString("assistant_name", asisten || "Kandar");
    prefs.setString("emergency_number", emergencyPhone.trim());
    prefs.setString("favorite_contact_name", contactName.trim());
    prefs.setString("favorite_contact_number", contactPhone.trim());

    prefs.setBoolean("reminder_enabled", medOn);
    const slots = [med1.trim(), med2.trim(), med3.trim()]
      .filter((s) => TIME_RE.test(s))
      .map((s) => {
        const [h, m] = s.split(":");
        return { h: parseInt(h, 10), m: parseInt(m, 10), en: true };
      });
    if (medOn && slots.length > 0) {
      prefs.setString("med_times", JSON.stringify(slots));
    }
    prefs.setBoolean("is_first_time", false);

    if (medOn) setReminders();

    void bridge.speak({
      text:
        `Semua siap! ${asisten || "Aku"} siap nemenin ${nenek || "Bu"} ngobrol. ` +
        "Silakan bicara kapan saja ya.",
      pitch: 0.85,
      rate: 0.75,
    });
    onFinish();
  };

  return (
    <div className="wizard">
      <h1>{TITLES[step]}</h1>
      <div className="subtitle">{SUBTITLES[step]}</div>

      {step === 0 && (
        <>
          <div className="field">
            <label>Nama Nenek (atau istilah panggilan)</label>
            <input type="text" value={nenekName} onChange={(e) => setNenekName(e.target.value)} />
            <div className="hint">Ini akan dipanggil oleh asisten saat berbicara</div>
          </div>
          <div className="field">
            <label>Nama Asisten</label>
            <input type="text" value={assistantName} onChange={(e) => setAssistantName(e.target.value)} />
            <div className="hint">Nama asisten yang menemani nenek</div>
          </div>
        </>
      )}

      {step === 1 && (
        <>
          <div className="field">
            <label>Nomor Telepon Darurat</label>
            <input
              type="tel"
              value={emergencyPhone}
              onChange={(e) => setEmergencyPhone(e.target.value)}
              placeholder="08xxxxxxxxxx"
            />
            <div className="hint">PENTING: Nomor yang akan dihubungi saat ada keadaan darurat.</div>
          </div>
          <div className="field">
            <label>Nama Kontak Favorit (Anak/Cucu)</label>
            <input type="text" value={contactName} onChange={(e) => setContactName(e.target.value)} />
          </div>
          <div className="field">
            <label>Nomor Telepon Favorit</label>
            <input
              type="tel"
              value={contactPhone}
              onChange={(e) => setContactPhone(e.target.value)}
              placeholder="08xxxxxxxxxx"
            />
            <div className="hint">Bilang 'Telpon anak' atau 'Nelpon cucu' untuk langsung menelpon.</div>
          </div>
        </>
      )}

      {step === 2 && (
        <>
          <div className="switch-row">
            <span>Aktifkan pengingat minum obat</span>
            <input
              type="checkbox"
              className="switch"
              checked={medOn}
              onChange={(e) => setMedOn(e.target.checked)}
            />
          </div>
          <div className="field">
            <label>Jadwal 1 (HH:mm)</label>
            <input type="text" value={med1} onChange={(e) => setMed1(e.target.value)} placeholder="08:00" />
          </div>
          <div className="field">
            <label>Jadwal 2 — opsional</label>
            <input type="text" value={med2} onChange={(e) => setMed2(e.target.value)} placeholder="14:00" />
          </div>
          <div className="field">
            <label>Jadwal 3 — opsional</label>
            <input type="text" value={med3} onChange={(e) => setMed3(e.target.value)} placeholder="20:00" />
          </div>
        </>
      )}

      {step === 3 && <div className="summary">{summary}</div>}

      <div className="btn-row">
        {step > 0 && (
          <button
            className="btn gray"
            onClick={() => {
              buzz();
              showStep(step - 1);
            }}
          >
            ← Kembali
          </button>
        )}
        <button className="btn" onClick={onNext}>
          {step === 3 ? "Mulai Ngobrol 🎤" : "Lanjut →"}
        </button>
      </div>

      <button
        className="btn gray"
        style={{ minHeight: 44, fontSize: "0.9rem" }}
        onClick={() => {
          prefs.setBoolean("is_first_time", false);
          onFinish();
        }}
      >
        Lewati setup
      </button>
    </div>
  );
}
