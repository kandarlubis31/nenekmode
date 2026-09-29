# app — Project Context

## Ringkasan
Modul aplikasi utama NenekMode — voice-first AI assistant Android untuk lansia dengan ChatGPT-like voice mode. Berisi 7 file Java: MainActivity (AudioRecord + Whisper STT, VAD, continuous mode, auto-interrupt, emergency SMS, battery check, tutorial), SettingsActivity (pengaturan lengkap), IntentMatcher (NLU offline dari JSON + getJsonString), GroqClient (API client Groq untuk LLM + Whisper dengan conversation context & timeout), WeatherClient (cuaca via Open-Meteo), ReminderReceiver (alarm obat), CheckInReceiver (alarm cek harian).

## Tech Stack
Android (compileSdk 34, minSdk 24), Java 8, OkHttp 4.12.0, AndroidX, Google Play Services Location, Groq Whisper API, Groq Llama-3.1-8B, Open-Meteo API, Android TTS, AudioRecord + VAD

## Struktur Utama
```
src/main/
  AndroidManifest.xml
  assets/
    intents.json
  java/com/example/nenekmode/
    MainActivity.java
    SettingsActivity.java
    IntentMatcher.java
    GroqClient.java
    WeatherClient.java
    ReminderReceiver.java
    CheckInReceiver.java
  res/
    layout/
      activity_main.xml
      activity_settings.xml
    drawable/
      logo.png, button_emergency.xml, rounded_background.xml, dst.
    values/
      colors.xml
build.gradle
CONTEXT.md
```

## Voice Pipeline
1. AudioRecord capture PCM 16kHz → WAV
2. VAD (RMS threshold 2.0f, silence 1.5s)
3. Groq Whisper API → teks
4. IntentMatcher (offline) atau Groq LLM (online)
5. Android TTS → speech output
6. Auto-interrupt → continuous loop

## Fitur Utama
- Continuous voice mode (tanpa sentuh layar)
- Auto-interrupt (potong jawaban saat user bicara)
- Obat reminder + Daily check-in
- Emergency SMS + GPS
- Battery warning otomatis
- Tutorial suara pertama kali
- Favorite contact (nelpon anak/cucu)
- High contrast mode + 3 ukuran font
- Conversation context (10 chat terakhir)
- Internet check + fallback ke intent lokal

## Cara Menjalankan
- Buka project di Android Studio, install dari `local.properties`
- Atau jalankan `./gradlew assembleDebug` dari root project

---

*Generated: 2026-08-31 · Path: java\NenekMode\app*