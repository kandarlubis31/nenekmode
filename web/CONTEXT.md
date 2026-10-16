# web — Project Context

## Ringkasan
Port NenekMode ke **React 19 + TypeScript + Vite + Capacitor 8** — app kedua yang install berdampingan app Java asli (`com.example.nenekmode.web`). UI modern di WebView; mesin kritis (alarm exact, Mode Teman/CompanionService, SMS darurat, TTS) tetap jalan di kode Java asli lewat plugin `NenekNative`. Semua fitur jalan juga di browser (fallback aman) buat dev cepat tanpa HP.

## Tech Stack
- Frontend: React 19, TypeScript ~6.0, Vite 8, oxlint
- Native: Capacitor 8.5 (Android), Gradle 8.14.3, compileSdk 36, **JDK 21 wajib**
- AI: Groq (Llama + Whisper large-v3) — key via `web/.env` (`VITE_GROQ_API_KEY`)
- Dev/test: puppeteer-core (smoke test UI), Node scripts (probe Groq)

## Struktur Utama
```
src/
  App.tsx                 - Router antar layar + aksesibilitas + sync prefs
  screens/
    WizardScreen.tsx      - Setup 4 langkah (nama, kontak, obat, selesai)
    MainScreen.tsx        - Status pill, bubble chat, tombol darurat/kesehatan/mic
    SettingsScreen.tsx    - 2 tab: Umum & Mode Teman/AI
    BoardScreen.tsx       - Papan info (jam besar, sholat, obat, cuaca)
  hooks/
    useAssistant.ts       - Orkestrator: quick commands -> matcher -> LLM -> marker -> TTS
  lib/                    - Port TS logika native: intents, conversation, memory,
                            prayer, news, weather, bp, escalation, scheduling,
                            receivers (fallback browser), recorder (VAD+WAV), prefs, groq
  native/
    bridge.ts             - Plugin NenekNative (TTS/alarm/SMS/telpon/SIM/ModeTeman/battery/geo)
    sync.ts               - localStorage <-> SharedPreferences (JS = settings,
                            native = state runtime/NATIVE_OWNED)
android/
  app/src/main/java/com/example/nenekmode/         - Mesin Java asli di-copy utuh
  app/src/main/java/com/example/nenekmode/web/    - MainActivity (BridgeActivity) + NenekNativePlugin
scripts/
  smoke.mjs               - Smoke test UI via Chrome headless (preview :4173)
  probe-groq.mjs          - Cek API key Groq
  debug-chat.mjs          - Debug alur chat
```

## Cara Menjalankan
- Dev browser: `npm run dev` (buka http://localhost:5173, fitur voice/TTS pakai fallback browser)
- Build web: `npm run build` (tsc -b && vite build → `dist/`)
- Sync ke Android: `npx cap sync android` (copy dist ke assets)
- Build APK: di `web/android` jalankan `gradlew assembleDebug` → `app/build/outputs/apk/debug/`
- Smoke test: `npm run build && npx cap sync android` → `vite preview --port 4173` → `node scripts/smoke.mjs`
- **Catatan JDK**: Capacitor 8 butuh JDK 21 (Temurin sudah terpasang). Jangan set JAVA_HOME ke jdk-17 (itu untuk app lama).

## Kontrak Bridge (NenekNative)
- `speak/stopSpeaking/isSpeaking` — TTS native resolve saat TTS selesai (id-ID)
- `scheduleAlarm/cancelAlarm/cancelAllAlarms` — AlarmManager exact, kode requestCode unik per jenis (lihat `lib/scheduling.ts` CODES)
- `sendSms/callNumber/hasActiveSim` — darurat; JS guard nomor kosong/placeholder
- `startCompanion/stopCompanion/isCompanionRunning` — CompanionService foreground (mic)
- `getBattery/hasMicPermission/requestMicPermission/getGeo`
- `syncPrefs/pullPrefs` — SharedPreferences "Nenek_Assistant", format `[type, value]` (b/i/l/f/s)

## Catatan Penting
- `web/.env` berisi `VITE_GROQ_API_KEY` — jangan commit
- Saat Mode Teman native jalan, WebView **tidak** listen mic (hindari dobel dengar) — lihat `useAssistant.startListeningLoop`
- State runtime (escalation, med streak, followup) dimiliki native; JS hanya baca via pull

---

*Generated: 2026-10-16 · Path: java\NenekMode\web*
