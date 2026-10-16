# NenekMode — Project Context

## Ringkasan
Aplikasi Android voice-first assistant berbasis AI untuk membantu dan menemani lansia. Menggunakan **ChatGPT-like voice mode** dengan Groq Whisper API (speech-to-text), Groq Llama-3.1-8B (AI), Open-Meteo API (cuaca), dan Android TTS (text-to-speech). Fitur continuous listening, auto-interrupt, obat reminder, daily check-in, emergency SMS + GPS, dan tutorial suara.

## Tech Stack
- **Platform**: Android (compileSdk 34, minSdk 24, targetSdk 34)
- **Build**: Gradle (Android Gradle Plugin 7.4.2)
- **Language**: Java 8
- **Libraries**: OkHttp 4.12.0, AndroidX (appcompat, material, constraintlayout, core), Google Play Services Location 21.0.1, vosk-android 0.3.75 + jna 5.13.0@aar (wake word offline)
- **AI/LLM**: Groq API (default Llama-3.1-8B-instant, model bisa diganti di Pengaturan / BYOK)
- **STT**: Groq Whisper API (whisper-large-v3) via AudioRecord + VAD
- **TTS**: Android TextToSpeech (id-ID locale, pitch 0.85, rate 0.75)
- **Weather**: Open-Meteo API
- **NLP**: Custom intent matcher dari `assets/intents.json`
- **Database**: SharedPreferences (chat history, settings, contacts)

## Struktur Utama
```
app/
  build.gradle
  src/main/
    AndroidManifest.xml
    assets/
      intents.json              - Intent patterns & responses (30+ intents)
    java/com/example/nenekmode/
      MainActivity.java         - Voice-first UI, Whisper STT, VAD, continuous mode,
                                  auto-interrupt, emergency SMS, battery check, tutorial
      SettingsActivity.java     - Pengaturan nama, kontak, aksesibilitas, reminder
      IntentMatcher.java        - Pattern matching dari intents.json + getJsonString()
      GroqClient.java           - API client Groq (LLM + Whisper), conversation context,
                                  timeout handling, error messages Indonesia
      WeatherClient.java        - API client Open-Meteo (cuaca current & forecast)
      ReminderReceiver.java     - Alarm receiver untuk obat reminder
      CheckInReceiver.java      - Alarm receiver untuk daily check-in
    res/
      layout/
        activity_main.xml       - UI utama (greeting, status, health/weather buttons,
                                  emergency button, chat history, tips)
        activity_settings.xml   - Settings (nama, aksesibilitas, reminder, kontak favorit)
      drawable/
        logo.png, rounded_background.xml, button_emergency.xml, dst.
      values/
        colors.xml              - Termasuk high contrast colors (hc_*)
  CONTEXT.md
web/                      - Port React+Vite+Capacitor (app berdampingan, lihat bawah)
  src/                    - Frontend React (screens, lib port logika native, native bridge)
  android/                - Proyek Capacitor Android (JDK 21, mesin Java asli di-copy utuh)
  scripts/                - smoke.mjs (puppeteer), probe-groq.mjs, debug-chat.mjs
gradle/
res/
.env (API keys)
build.gradle
settings.gradle
gradle.properties
gradlew, gradlew.bat
local.properties
CONTEXT.md
```

## Arsitektur

### Voice Pipeline (ChatGPT-like Mode)
1. **AudioRecord** capture PCM 16kHz → WAV file
2. **VAD (Voice Activity Detection)** — RMS threshold 2.0f, silence 1.5s, min speech 400ms
3. **Groq Whisper API** — transkripsi audio ke teks (Bahasa Indonesia)
4. **IntentMatcher** — pattern matching dari intents.json (offline)
5. **Groq LLM** — AI response dengan conversation context (online)
6. **Android TTS** — speech output (id-ID, pitch 0.85, rate 0.75)
7. **Auto-interrupt** — jika user ngomong saat TTS aktif, langsung dipotong
8. **Continuous listening** — setelah jawab, langsung dengar lagi (300ms delay)

### State Machine
- `IDLE` → `LISTENING` → `PROCESSING` → `SPEAKING` → `LISTENING` (loop)
- Auto-interrupt: `SPEAKING` → `LISTENING` (langsung tanpa delay)

### Intent Actions
| Action | Fungsi |
|--------|--------|
| `get_greeting` | Sapaan berdasarkan waktu |
| `get_time` | Jam sekarang |
| `get_date` | Tanggal sekarang |
| `get_weather` | Cuaca via GPS |
| `get_forecast` | Ramalan cuaca besok |
| `get_health` | Info obat reminder |
| `get_help` | Panduan fitur |
| `get_battery` | Level baterai HP |
| `get_favorite_contact` | Nelpon kontak favorit |
| `trigger_emergency` | Panic + SMS GPS |
| `repeat_last` | Ulangi jawaban terakhir |

### Voice Commands
- "Berhenti" / "Stop" / "Selesai" → stop listening
- "Ulangi" / "Ulang" → ulangi jawaban terakhir
- "Bantu" / "Tolong" → panduan fitur
- "Telpon anak" / "Nelpon cucu" → nelpon kontak favorit
- "Baterai" → info level baterai

### Features
- **Continuous Voice Mode** — tanpa sentuh layar, asisten selalu dengar
- **Auto-interrupt** — potong jawaban asisten saat user bicara
- **Obat Reminder** — alarm + notifikasi via ReminderReceiver
- **Daily Check-in** — alarm + notifikasi via CheckInReceiver
- **Emergency SMS + GPS** — auto SMS dengan Google Maps link saat panic
- **Battery Warning** — announce otomatis jika baterai ≤ 20%
- **Tutorial Voice** — panduan suara saat pertama kali buka app
- **Favorite Contact** — nelpon anak/cucu dengan satu kalimat
- **High Contrast Mode** — mode gelap dengan warna kontras tinggi
- **Font Size** — 3 ukuran (normal/large/xlarge)
- **Conversation Context** — kirim 10 chat terakhir ke Groq API
- **Internet Check** — fallback ke intent lokal jika offline

## Permissions
| Permission | Fungsi |
|-----------|--------|
| `RECORD_AUDIO` | AudioRecord untuk STT |
| `INTERNET` | Groq API + Open-Meteo |
| `CALL_PHONE` | Emergency call + favorite contact |
| `SEND_SMS` | Emergency SMS dengan GPS |
| `ACCESS_FINE_LOCATION` | GPS untuk cuaca & SMS |
| `ACCESS_COARSE_LOCATION` | Location fallback |
| `RECEIVE_BOOT_COMPLETED` | Restart alarm setelah reboot |
| `POST_NOTIFICATIONS` | Notifikasi reminder & check-in |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Watchdog minta pengecualian biar service tidak dibunuh sistem |
| `VIBRATE` | Vibrasi saat panic |

## Cara Menjalankan
- Buka project di Android Studio, install dari `local.properties`
- Atau jalankan `./gradlew assembleDebug` dari root project
- **WAJIB JDK 17**: Java 21 di PATH tidak kompatibel dengan Gradle 7.5.1 — di Git Bash: `JAVA_HOME="/c/Program Files/Java/jdk-17" ./gradlew assembleDebug`
- Membutuhkan Android SDK yang terinstal dan emulator/perangkat Android
- Mic permission diperlukan untuk voice mode
- `jna` di-pin `5.13.0@aar` + exclude transitive dari vosk-android — JNA 5.18.1 gagal dexing di AGP 7.4.2 (fix 2026-09-17)

## Build Status (2026-09-17)
- APK debug terakhir: `app/build/outputs/apk/debug/app-debug.apk` (~46 MB) — **BELUM memuat rombak UI + escalation check-in** (sudah compile BUILD SUCCESSFUL). Jalankan `assembleDebug` ulang sebelum install
- **PENTING — insiden drive D (2026-09-17):** setelah hibernate, drive D tidak termount (`D:` hilang). `.gradle` (symlink → `/d/appdata/.gradle`) dan Android SDK (`AppData\Local\Android` junction → `D:\appdata\Android`) semuanya di D → build gagal total. **SUDAH DIPERBAIKI** via restart penuh. Pelajaran: hindari hibernate di laptop ini — gunakan shutdown/sleep. Daemon Gradle lama juga harus di-stop (`./gradlew --stop`) setelah restart.
- Ukuran besar karena Vosk membawa native `.so` 4 ABI; opsi ramping: `ndk { abiFilters 'arm64-v8a' }` → ±15 MB (belum diterapkan)

## Companion Mode (Update 2026-09)
Konsep: HP diletakkan dekat lansia (disabilitas) sebagai **teman ngobrol**, bukan sekadar asisten.
- **CompanionService** — foreground service (type microphone) keep-alive: dengar terus walau layar mati/app ditutup. Toggle "Mode Teman" di layar utama / settings. Mati hanya via "tutup asisten"; "berhenti" = jeda 1 menit.
- **Asisten proaktif** — ProactiveReceiver nyapa duluan (default 09:30 & 15:30, bisa diatur di prefs `proactive_times`), ajak kuis/nostalgia sesuai hari.
- **Berita pagi** — RSS detik.com dibacakan saat sapaan pagi (toggle `news_enabled`).
- **Kuis & doa** — ContentBank lokal (12 kuis, doa pagi/siang/sore/malam). "mulai kuis", "doa".
- **Mode nostalgia** — "cerita dulu" → semua cerita mengalir ke LLM tanpa disadap, keluar via "udahan".
- **Memori jangka panjang** — MemoryStore ekstrak fakta dari ucapan ("anak saya...", "saya alergi...") → di-inject ke system prompt LLM. "hapus ingatan" untuk reset.
- **Wake word (fase 1)** — `wake_mode=on`: input harus mengandung nama asisten (kecuali sedang dalam alur kuis/obat/nostalgia). Fase 2: Vosk offline (~40MB).
- **Lagu lama** — "puter lagu" → buka YouTube search otomatis.
- **Multi-jadwal obat** — 3 slot/hari (prefs `med_times` JSON) + konfirmasi suara "sudah/belum" + follow-up 15 menit (MedConfirmReceiver) + snooze 10 menit.
- **BootReceiver** — re-schedule semua alarm setelah reboot.
- **AlarmScheduler** — fallback inexact alarm jika izin SCHEDULE_EXACT_ALARM tidak ada (Android 12+).
- **GroqStreamingClient** — streaming SSE per kalimat → TTS langsung ngomong tanpa nunggu jawaban penuh; Whisper pindah ke whisper-large-v3.
- **AudioUtil** — VAD + rekam bersama (dipakai Activity & Service).

File baru: `AudioUtil, AlarmScheduler, BootReceiver, CompanionService, ConversationEngine, ContentBank, MemoryStore, NewsClient, GroqStreamingClient, MedConfirmReceiver, ProactiveReceiver, SpeechUtil, NotificationManagerHelper`

- **Jadwal adzan** — Aladhan API (method Kemenag, gratis tanpa key) via GPS tersimpan (`last_lat/last_lon`). Alarm 5 waktu + refresh harian 00:10 + tahan reboot. Tanya "adzan maghrib jam berapa?" / "jadwal adzan". Toggle di settings (`adzan_enabled`).

## Fitur Opsional (toggle di Pengaturan, 2026-09)
- **Pengingat minum air** — interval bisa diatur (default 120 menit, min 15) via `WaterReceiver`, + konfirmasi suara. Hening saat mode malam.
- **Cerita & nasihat** — "ceritain dongeng" → 8 kisah/hikmah lokal (`ContentBank.STORIES`). Toggle `stories_enabled`.
- **Peringatan cuaca pagi** — sapaan proaktif pagi menyertakan warning hujan/badai (Open-Meteo sync). Toggle `weather_alert_enabled`.
- **Catat tensi darah** — "tekanan darahku 130 atas 85" → tersimpan maks 30 entry (`BloodPressureStore`), kategori normal/agak tinggi/tinggi/sangat tinggi + saran. Tanya "berapa tekanan darahku?". Toggle `bp_enabled`.
- **Sapaan ulang tahun** — daftar tanggal "MM-dd" dipisah koma (`birthday_list`), cek harian 00:05 via `BirthdayReceiver`. Toggle `birthday_enabled`.
- **Mode malam** — jam tidur (default 21:00-05:00): proaktif & minum air dijeda, adzan tetap bunyi. `ConversationEngine.isNightMode()`.

- **BYOK (Bring Your Own Key)** — toggle di Pengaturan: keluarga bisa tempel API key Groq sendiri (`byok_api_key`) + pilih model (Llama 3.1 8B cepat / Llama 3.3 70B pintar / GPT-OSS 20B hemat) + tombol tes koneksi (`/models`). Kosong/OFF = fallback ke key bawaan (`BuildConfig.GROQ_API_KEY`). Resolver: `GroqClient.resolveApiKey/resolveModel`.

- **Wake word offline fase 2 (Vosk)** — `vosk-android:0.3.75` + `WakeWordManager` (download model .zip dari URL custom — model Indonesia tidak tersedia resmi, bisa pakai build komunitas/kaldi-id sendiri). Saat idle, CompanionService pakai `voskIdleListen()` (offline, tanpa cloud); wake word kedengar → "Ada, Bu!" → loop VAD+Whisper normal. Model belum ada → fallback gate nama fase 1. Toggle `vosk_enabled` + URL model di Pengaturan.
- **Rate-limit guard BYOK** — key BYOK kena 429 → retry otomatis sekali pakai key bawaan (chat, streaming, Whisper), cooldown flag `byok_cooldown`, sukses = cooldown clear (self-healing). Asisten tidak pernah diam.

## Penyempurnaan Companion (2026-09-17)
- **Follow-up emosional** — keluhan ("pusing", "capek", "gak enak badan", dll) tersimpan (`pending_followup`); 30 menit–24 jam kemudian asisten nanya balik "udah membaik belum?" — di sapaan pembuka (MainActivity & CompanionService) atau di tengah obrolan; jawaban sudah/belum → clear + kalimat hangat. `ConversationEngine.detectComplaint/storeFollowup/getFollowupQuestion`.
- **Streak minum obat** — konfirmasi "sudah" dihitung (`med_streak`/`med_streak_date`); ≥3 hari berturut → pujian otomatis saat konfirmasi; ikut dibacakan di tombol kesehatan.
- **Riwayat tensi darah** — tombol kesehatan & `get_health` sekarang bacakan 3 catatan sebelumnya (`BloodPressureStore.count/speechHistory`), bukan cuma terakhir.
- **Backup & restore** — `BackupUtil` via SAF: export semua prefs utama ke `nenekmode_backup.json` (API key/chat/state runtime dikecualikan), restore + auto-restart app. Tombol di Pengaturan.
- **Volume malam soft** — mode malam: TTS turun ke 30% volume (MainActivity `speakResponse`, CompanionService `speak`/`queueSentence`/`speakNext`) — asisten tetap nyahut tapi pelan.
- **Sapaan variasi** — `GreetingBank` (4 variasi per waktu, hangat) dipakai ProactiveReceiver & pengingat obat, gak monoton lagi.- **Watchdog Mode Teman** — `WatchdogReceiver` alarm berkala ±15 menit (requestCode 1200): kalau `keep_alive` aktif tapi service mati (dimatikan sistem/OEM killer) → start ulang otomatis + notifikasi foreground muncul lagi; minta pengecualian battery optimization sekali (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); jaring pengaman re-schedule alarm obat/proaktif/adzan. Nyala saat app dibuka, saat Mode Teman distart, saat reboot, lalu self-perpetuating. Catatan OEM (Xiaomi/Oppo/Vivo): keluarga tetap disarankan aktifkan "autostart" + kunci app di recents.
- **Mode papan info** — `BoardActivity` (layout `activity_board.xml`): HP diparkir jadi jam meja pintar — layar tetap nyala redup (brightness 0.02 + keep-screen-on) menampilkan jam besar, tanggal Indonesia, sholat & obat berikutnya (`PrayerTimes.nextPrayerShort`), cuaca, status Mode Teman + micro-watchdog, dan baterai. Toggle tombol "Papan Info" di layar utama; buka app utama = papan nonaktif; dipertahankan saat reboot via BootReceiver; `showWhenLocked` + `turnScreenOn`.

File baru: `BackupUtil, GreetingBank, WatchdogReceiver, BoardActivity (+ res/layout/activity_board.xml)` · Tambahan escalation: `EscalationManager, EscalationReceiver`

## Escalation Check-in (2026-09-17, compile BUILD SUCCESSFUL)
- **Jaring pengaman cek harian** — `EscalationManager` + `EscalationReceiver`: saat `CheckInReceiver` menyapa, alarm 30 menit (requestCode 1400) dipasang & flag `escalation_armed` disimpan (tahan reboot). Lansia bicara (transkripsi sukses di MainActivity/CompanionService, atau sinyal VAD selesai bicara di service) → `cancelEscalation()`. Alarm tetap mati tanpa respons → **SMS otomatis ke nomor darurat**: "[nama] tidak merespons cek harian (30 menit). Mohon segera hubungi beliau." — sekali per sesi cek. Reboot saat armed → `rearmIfArmed()` pasang ulang sisa waktunya (sudah lewat → langsung eskalasi). Toggle di Pengaturan (tab Umum, default ON, `escalation_enabled`).
- **Deteksi SIM untuk darurat** — `hasActiveSim()` (TelephonyManager: SIM_STATE_ABSENT / Line1Number kosong → dianggap tanpa SIM; fallback aman = true bila error deteksi) di MainActivity & CompanionService. Panggilan darurat diblokir + peringatan suara sekali (`sim_warning_shown`) bila SIM tak terpasang; SMS darurat service juga gak terkirim percuma. Master switch `call_enabled` (default ON).

## Rombak UI/UX (2026-09-17, compile BUILD SUCCESSFUL)
- **Layar utama baru** — kartu status besar (ikon + teks) dengan **warna berubah per state**: hijau=Mendengarkan, kuning=Mengenali/memproses, biru=Menjawab, merah=Kepanikan (`bg_status_pill.xml` + `updateStatusUI`). Tombol darurat dipindah ke **paling bawah (zona jempol)**, tips dihapus, riwayat jadi **bubble chat** (`bg_bubble_user/assistant.xml`): ucapan lansia kanan (aksen), asisten kiri (putih). Riwayat lama tetap dimuat; log plain tetap disimpan di `chat_history` (dipakai context LLM).
- **Haptic feedback** — `setupHaptics()` di semua tombol besar (ACTION_DOWN → `performHapticFeedback`), click listener tetap jalan.
- **Setup wizard 4 langkah** — `SetupWizardActivity` + `activity_setup_wizard.xml`: (1) nama lansia+asisten, (2) nomor darurat+kontak favorit, (3) jadwal obat (input `HH:mm`, bisa dilewati), (4) ringkasan+selesai. Menggantikan tutorial teks; dipicu saat `is_first_time`; langsung simpan prefs + `ReminderReceiver.setReminders`.
- **Settings tab kategori** — 2 tab: **Umum** (nama, darurat, kontak, aksesibilitas, obat, cek harian, backup) & **Mode Teman & AI** (mode teman, BYOK, Vosk, fitur opsional, mode malam). Semua ID widget lama dipertahankan — `SettingsActivity` tinggal wiring tab (`selectTab`).
- File baru UI: `SetupWizardActivity (+ activity_setup_wizard.xml, bg_status_pill, bg_bubble_user, bg_bubble_assistant)`

## Port Web — React + Vite + Capacitor (2026-10-16)
App kedua, **install berdampingan** app Java asli (`applicationId com.example.nenekmode.web`). Tujuan: UI modern + bisa di-dev/browser, tapi mesin native (alarm, Mode Teman, SMS darurat) tetap pakai kode Java asli.
- **Frontend** (`web/src`): React 19 + TypeScript + Vite. Layar: `WizardScreen` (setup 4 langkah), `MainScreen` (bubble chat + status pill), `SettingsScreen` (2 tab), `BoardScreen` (papan info). Orkestrator: `hooks/useAssistant` — alur identik MainActivity+CompanionService (quick commands → matcher lokal → LLM → marker output), voice loop VAD→WAV→Whisper via `lib/recorder`. Logika native di-port ke TS di `lib/` (intents, conversation, memory, prayer, news, weather, bp, escalation, scheduling, receivers untuk fallback browser).
- **Bridge** (`web/src/native/bridge.ts`): plugin Capacitor `NenekNative` — TTS, alarm exact, SMS, telpon, SIM, Mode Teman, battery, geo. Di browser semua fallback aman (speechSynthesis, setTimeout, Notification API) — dev tetap jalan tanpa HP.
- **Native side** (`web/android`): Gradle 8.14.3 + compileSdk 36 + **JDK 21** (Capacitor 8 wajib JDK 21; JDK 17 di app lama tetap untuk build app lama). Semua receiver/service Java asli di-copy utuh (`com.example.nenekmode.*`) + `NenekNativePlugin` (19 method) + `MainActivity` (BridgeActivity).
- **Sinkron prefs** (`web/src/native/sync.ts`): localStorage = sumber kebenaran settings (push ke SharedPreferences debounce 400ms); native = sumber kebenaran state runtime (`NATIVE_OWNED`: med_confirm_waiting, escalation_armed, med_streak, dst) — di-pull + merge pas app ke foreground. `chat_history` digabung per-baris.
- **Dev**: `npm run dev` (browser), `npm run build` (tsc+vite, lolos), `npx cap sync android`, smoke test `node scripts/smoke.mjs` (puppeteer-core + Chrome sistem, preview :4173, 6 screenshot di `web/smoke/`), debug Groq `scripts/probe-groq.mjs` / `debug-chat.mjs`. API key: `web/.env` (`VITE_GROQ_API_KEY`, jangan commit).
- Status: web build BUILD SUCCESSFUL; APK Capacitor build belum tentu lolos (cek `web/android/app/build/outputs/apk/debug/`). Belum ditest di HP asli.

## Catatan Penting
- `.env` berisi dua API key (Groq) — jangan commit
- Groq API key dibaca dari `BuildConfig.GROQ_API_KEY` (local.properties) atau BYOK user — dipakai LLM **dan** Whisper
- Nomor darurat dibaca dari pengaturan (`emergency_number`) — tanpa nomor = toast peringatan, bukan telpon kosong
- Whisper via Groq **gratis** dan **cepat** (groq.com/models)
- Temp WAV file di-cache dan dihapus setelah transkripsi
- Chat history disimpan di SharedPreferences (max 10 entry untuk context)

---

*Generated: 2026-08-31 · Path: java\NenekMode*