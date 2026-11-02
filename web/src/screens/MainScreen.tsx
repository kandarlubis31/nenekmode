import { useEffect, useRef, useState } from "react";
import { useAssistant } from "../hooks/useAssistant";

interface Props {
  onOpenSettings: () => void;
  onOpenBoard: () => void;
}

const STATUS_ICONS: Record<string, string> = {
  listening: "🎤",
  thinking: "🤔",
  speaking: "💬",
  panic: "🚨",
  info: "👋",
};

export default function MainScreen({ onOpenSettings, onOpenBoard }: Props) {
  const a = useAssistant();
  const chatRef = useRef<HTMLDivElement>(null);
  const barRef = useRef<HTMLDivElement>(null);
  const [draft, setDraft] = useState("");

  useEffect(() => {
    chatRef.current?.scrollTo({ top: chatRef.current.scrollHeight });
  }, [a.chatLines]);

  // Ukur bar bawah tetap -> cadangan ruang di layar disetel sama persis.
  // Dulu ruangnya ditebak (236px) padahal bar bisa lebih tinggi, jadi tombol
  // fitur terakhir terselip beberapa pixel di balik bar; dan saat font
  // diperbesar bar ikut membesar sehingga tebakan tetap meleset.
  useEffect(() => {
    const el = barRef.current;
    if (!el) return;
    const apply = () => {
      const h = Math.ceil(el.getBoundingClientRect().height);
      if (h > 0) document.documentElement.style.setProperty("--bar-space", `${h}px`);
    };
    apply();
    const clear = () => document.documentElement.style.removeProperty("--bar-space");
    if (typeof ResizeObserver !== "undefined") {
      const ro = new ResizeObserver(apply);
      ro.observe(el);
      return () => {
        ro.disconnect();
        clear();
      };
    }
    window.addEventListener("resize", apply);
    return () => {
      window.removeEventListener("resize", apply);
      clear();
    };
  }, []);

  return (
    <div className="screen main">
      <div className={`status-card ${a.statusKind}`}>
        <div className="status-icon">{STATUS_ICONS[a.statusKind] ?? "👋"}</div>
        <div className="status-text" role="status" aria-live="polite">{a.statusText}</div>
      </div>

      <div className="greeting-row">
        <span className="greeting">{a.greetingText}</span>
        <button
          className="btn gray"
          style={{ flex: "none", minHeight: 44, padding: "8px 14px" }}
          onClick={a.tapMic}
        >
          {a.listening ? "Stop 🎤" : "Bicara 🎤"}
        </button>
      </div>

      <div className="checkin-status">
        {a.checkInEnabled
          ? `Cek harian: Aktif - ${a.checkInTime}`
          : "Cek harian: Tidak aktif"}
      </div>

      <div className="chat-box" ref={chatRef} role="log" aria-live="polite" aria-label="Riwayat obrolan">
        {a.chatLines.map((line, i) => {
          const isSystem =
            !line.isUser &&
            (line.raw.includes("Sistem mendeteksi") ||
              line.raw.includes("Melakukan panggilan"));
          return (
            <div
              key={i}
              className={`bubble ${line.isUser ? "user" : isSystem ? "system" : "assistant"}`}
            >
              {line.raw}
            </div>
          );
        })}
      </div>

      <div className="btn-row">
        <button className="btn" onClick={a.healthButton}>💊 Kesehatan</button>
        <button className="btn accent" onClick={a.weatherButton}>🌤️ Cuaca</button>
      </div>

      <div className="btn-row">
        <button className={`btn ${a.companionOn ? "" : "gray"}`} onClick={() => void a.toggleCompanion()}>
          Mode Teman: {a.companionOn ? "ON" : "OFF"}
        </button>
        <button className="btn gray" onClick={onOpenBoard}>🕐 Papan Info</button>
        <button className="btn" onClick={onOpenSettings}>⚙️ Pengaturan</button>
      </div>

      {/* bar bawah tetap: mic + ketik + darurat SELALU terlihat walau layar
          pendek (di 360x640 tombol darurat dulu ada di posisi 842px = di bawah
          lipatan, harus scroll dulu baru bisa menekannya). */}
      <div className="bottom-bar" ref={barRef}>
        <div className="bottom-bar-inner">
          <button
            className={`btn mic ${a.listening ? "on" : ""}`}
            onClick={a.tapMic}
          >
            {a.listening ? "🔴 Dengar terus — tekan buat berhenti" : "🎤 Tahan / tekan buat ngomong"}
          </button>

          <div className="btn-row">
            <input
              type="text"
              className="chat-input"
              value={draft}
              aria-label="Ketik pesan buat asisten"
              placeholder="Ketik pesan buat asisten..."
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && draft.trim()) {
                  a.processInput(draft.trim());
                  setDraft("");
                }
              }}
            />
            <button
              className="btn"
              style={{ flex: "none", minWidth: 90 }}
              onClick={() => {
                if (draft.trim()) {
                  a.processInput(draft.trim());
                  setDraft("");
                }
              }}
            >
              Kirim ➤
            </button>
          </div>

          <button className="btn emergency" onClick={() => void a.emergency()}>
            🚨 DARURAT — Panggil Keluarga
          </button>
        </div>
      </div>
    </div>
  );
}
