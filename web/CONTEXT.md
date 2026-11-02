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
  dup-check.mjs           - Uji anti-duplikasi chat (bubble 1↔1)
  echo-guard-check.mjs    - Uji garda gema TTS (11 kasus)
  audit-check.mjs         - Uji runtime audit (mic/settings/papan; 12 kasus)
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

## Audit & Perbaikan (2026-10-17)
Audit versi web (banding app Java + uji runtime `scripts/audit-check.mjs`, **12/12 PASS**). Yang diperbaiki:
- **KRITIS — sync prefs native menolak float**: `NenekNativePlugin.syncPrefs` tak punya case `"f"` → `putString(getString)` atas number melempar `JSONException` → **seluruh batch sync gagal** (settings native basi; alarm baca setelan usang). Ditambah case `"f"` (`putFloat`) + tiap key dibungkus try. JS: `last_lat/last_lon` pakai `setNumber` (bukan `setJson`).
- **KRITIS — alarm adzan tak pernah dijadwalkan**: `prayer.setPrayerAlarmBridge` tak pernah dipanggil. Kini didaftarkan di `lib/scheduling.ts` (map ke `bridge.scheduleAlarm/cancelAlarm`).
- **Saklar "Aktifkan pengingat" tak bisa OFF**: kini `master && anySlot` (dan `applyReminderSetting` ikut nilai itu).
- **Default `companion_enabled` diseragamkan `false`** (Pengaturan = scheduler = receiver); saat OFF, save `cancelAlarm(proactive)`.
- **LLM dikirimi daftar intent**: `matcher.getJsonString()` (sebelumnya `""`).
- **Watchdog** dijadwalkan ulang tiap 6 jam di `rescheduleAllOnStart`.
- **Mode Papan** kini bisa diaktifkan: tombol di `BoardScreen` menyetel `board_mode` (ala `BoardActivity`).
- **Bersih-bersih**: buang `onTouchStart.preventDefault()` yang no-op (listener passive → warning konsol tiap sentuh), hapus self-assign `engineRef`, `Switch` pindah keluar render, validasi nomor terima nomor pendek (mis. 112).

Perbedaan sadar dari app Java (bukan bug): rute matcher lokal didahulukan saat online (hemat token) — Java selalu LLM; `onAssistantResponse` juga hanya di jalur LLM di kedua versi.

## Audit & Perbaikan Suara (2026-10-18)
Keluhan: "kenapa ketika pertama dibuka muncul suara" (suara kepotong / dobel / muncul telat). Akar masalah & perbaikan:
- **Antrian TTS** (`native/bridge.ts`) — native `speak` memakai `QUEUE_FLUSH`, jadi ucapan yang enak-enak berdekatan (sapaan + peringatan baterai + sapaan wizard) saling menimpa dan call yang di-flush bisa tak pernah resolve → mic tidak pernah mulai lagi. Kini `speak()` serial lewat antrean, ada watchdog timeout `max(6s, len*130ms)`, `stopSpeaking()` membatalkan ucapan yang masih antre (generation counter), dan `ttsIsSpeaking()` ikut menghitung antrean+mendengar-yang-sedang-jalan.
- **Sapaan ganda di wizard** — `WizardScreen.saveAndFinish` dulu ikut bicara "Semua siap!" bareng sapaan `MainScreen` → dihapus; sapaan pembuka hanya satu sumber.
- **Sapaan pembuka telat** — timer sapaan dulu dipasang *setelah* `await getLastLocation()` (bisa 8 dtk) + baterai → kini dipasang langsung, hanya menunggu 1 dtk.
- **Saklar baru `greeting_on_open`** (Pengaturan → 🔊 Suara, default ON) — kalau OFF, app tidak bersuara sendiri saat dibuka, cukup tombol Bicara.
- **Native: ucapan pertama dibuang** — `speak` dulu menunggu TTS init hanya 500 ms lalu resolve tanpa suara (sapaan pertama hilang). Kini `waitForTts` menahan call sampai ready (maks ~6 dtk, 12×500 ms) dan `stopSpeaking` me-resolve semua `pendingCalls`.
- **Anti-gema** — batas tunggu TTS di `startListeningLoop` dinaikkan 10 dtk → 60 dtk (ucapan panjang seperti berita/cerita bisa lebih lama; kalau kelewat, mic dibuka saat asisten masih bicara).
Bukti: `npm run build` lolos, smoke **13/13 PASS** (`scripts/smoke.mjs` kini men-stub `speechSynthesis` dan memastikan ucapan tidak tumpang tindih + cek saklar Suara), APK debug di-build ulang dan rantai aset identik (`dist` = `assets/public` = dalam APK, md5 sama).

## Audit & Perbaikan Website (2026-10-18, sesi web)
Fokus: versi **website** (browser) disempurnakan + seluruh bug yang terbukti diperbaiki. Semua klaim di bawah punya bukti uji; **APK tidak di-build ulang** di sesi ini.

### Bug yang diperbaiki (terbukti)
- **Kalimat user dikirim DUA KALI ke LLM** (`hooks/useAssistant.ts`). `addToHistory()` menulis baris `Anda: ...` lebih dulu, lalu riwayat itu ikut dikirim sebagai `messages` *plus* `userText` terpisah. Dibuktikan dengan repro: sebelum perbaikan `messages` memuat 2 pesan user identik; sesudah = 1. Kini baris terakhir dibuang kalau sama dengan input sekarang.
- **"Baca berita" tidak jalan di browser** (`lib/news.ts`). `rss.detik.com` tidak mengirim header CORS → fetch selalu gagal (probe: `ERR Failed to fetch`). Ditambah sumber kedua CORS-friendly (`berita-indo-api`/CNN, probe: `200`) + RSS hanya dicoba di native (biar tidak menumpuk error merah di konsol). Judul tak layak TTS (`VIDEO: ...`) dibuang, duplikat disaring, lalu **isi berita kini juga tampil di chat** (dulu bubble-nya cuma `[Berita]`).
- **Jawaban kuis resmi ditolak karena salah ketik**: data berisi `"punckak jaya"` → nenek yang menjawab "puncak jaya" dibilang salah. Diperbaiki + pencocokan jawaban diperlonggar (urutan kata/tanda baca: "merah dan putih" benar untuk "merah putih"; "puncak" diterima; jawaban salah tetap ditolak). Ada uji unit `scripts/unit-check.mjs` (30 pemeriksaan).
- **Kuis hilang saat layar dibangun ulang** (`lib/conversation.ts`): `currentQuiz` cuma field objek engine, sedangkan `quiz_waiting` tersimpan di prefs → buka Pengaturan lalu jawab = asisten bilang "Hampir benar! Jawabannya ." (kosong). Soal+jawaban kini ikut disimpan (`quiz_current`/`quiz_answer`), dan kalau memang hilang asisten bilang apa adanya.
- **Baterai basi**: pesan baterai memakai angka saat app dibuka, jadi setelah dicharge asisten masih bilang baterai rendah → sekarang dibaca ulang saat ditanya (`speakBattery`). Papan Info juga memperbarui baterai tiap 30 dtk (dulu sekali saja) dan jamnya berganti tepat di menit baru (dulu interval 30 dtk).
- **Mematikan eskalasi tidak membatalkan pemantauan** yang sudah armed → `disarmEscalation()` dipanggil saat saklar eskalasi/cek harian dimatikan, dan `trigger()` menolak jalan kalau saklarnya OFF.
- **Saklar "Aktifkan pengingat" menyala tanpa jam aktif** disimpan diam-diam sebagai OFF → kini ditolak dengan penjelasan (toast). Wizard juga selalu memasang alarm saat pengingat diaktifkan walau kolom jam dikosongkan (pakai jadwal yang ada/default).
- **Sapaan di layar berubah acak tiap status berubah** (dipanggil saat render) → dihitung sekali per pemasangan layar.
- **Riwayat chat tak terbatas** → dibatasi 300 baris (localStorage bisa penuh & tiap sync ke native mengirim string raksasa).
- **Konfirmasi obat/air kaku**: hanya menerima `ya`/`nggak` persis → kini mengenali "iya", "yah", "beres", "gak/ga/enggak/kagak" (per kata, jadi "pagi" tidak dihitung "ya").
- **Catatan follow-up basi memblokir keluhan baru** (`storeFollowup`) → yang >24 jam diganti.
- **Mic bisa menggantung** (`lib/recorder.ts`): AudioContext yang ter-suspend (autoplay policy) atau callback audio yang berhenti saat app di-background membuat promise rekaman tak pernah selesai (tombol mic macet) → `ctx.resume()` + watchdog `MAX_RECORDING_MS + 3 dtk`.
- **Izin mikrofon ditolak = diam** → status sekarang menjelaskan ("Izin mikrofon belum diberikan...").
- **Timer dengar tidak dibatalkan saat unmount** → bisa ada dua pipeline mic setelah kembali dari Pengaturan; kini dibatalkan + flag `disposedRef`.

### UI / aksesibilitas / HTML
- **Pengaturan ukuran font akhirnya berlaku menyeluruh**: dulu hanya bubble & status (rem tidak ikut karena `html` font-size tetap 16px) → kini akar rem ikut 16/20/24px sehingga tombol, label, dan input ikut membesar.
- **Kontras mode kontras tinggi**: teks putih di atas hijau `#00e676` hampir tak terbaca → teks jadi hitam untuk tombol/tab/status/bubble user, hint dibaca tetap terang.
- `:focus-visible` untuk keyboard/switch, `env(safe-area-inset-*)` biar tak ketutup notch/navigation bar, `aria-live` pada status & riwayat chat, `aria-label` pada input jam/menit obat dan kolom chat.
- `index.html`: `lang="id"`, judul "Nenek Mode", deskripsi, `theme-color`, meta PWA/Apple, `viewport-fit=cover`.

### Perilaku yang SENGAJA tidak diubah (bukan bug)
- Tombol mic "berhenti": kode lama sudah benar (ada `return` dini setelah `recordWithVad` saat `stopFlag` aktif). Perubahan yang saya coba (mematikan loop permanen saat bilang "berhenti") **dibatalkan** karena menyimpang dari app Java.
- Dedupe riwayat memakai Set sepanjang riwayat: dipertahankan (self-heal bug spam lama; uji `dup-heal-check.mjs` menangkap saat saya coba ubah jadi "hanya berurutan").
- Gate wake word (`passesWakeGate`) tetap hanya dipakai di CompanionService native, bukan di jalur input MainActivity — sama seperti app Java.

### Bukti uji (semua pada bundle terakhir)
- `npx tsc --noEmit` exit 0 · `npm run build` exit 0 · `npm run lint` exit 0 (3 warning pra-eksisting)
- `scripts/unit-check.mjs` → **UNIT: ALL PASSED** (30 pemeriksaan, exit 0)
- `scripts/audit-check.mjs` → **28/28 PASS**, exit 0, tanpa error konsol (naik dari 12; `process.exit` kini mengikuti hasil, dulu selalu 0). Uji baru: font besar+HC, sapaan stabil, riwayat LLM tanpa duplikat, izin mic ditolak, eskalasi dibatalkan, validasi pengingat, berita di browser, kuis tahan remount, perilaku tombol mic.
- `scripts/smoke.mjs` 13/13 PASS · `echo-guard-check` lulus · `dup-check` lulus · `dup-heal-check` PASS (exit 0)

---

*Generated: 2026-10-16 · Path: java\NenekMode\web*
