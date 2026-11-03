# NenekMode

A voice-first Android assistant for elderly users. Everything is driven by speech — large
targets, Indonesian TTS at a slow pitch and rate, and continuous listening so nobody has to
press a button to be heard.

Built for grandparents who find touch interfaces hard and phone menus impossible.

## Features

- **Continuous listening** with voice activity detection, plus auto-interrupt so the assistant
  stops talking when the user starts speaking
- **Speech in, speech out** — Groq Whisper (`whisper-large-v3`) for transcription, Android
  `TextToSpeech` in `id-ID` (pitch `0.85`, rate `0.75`) for replies
- **Offline wake word** via Vosk (`vosk-android`), so the phone reacts without sending audio
  anywhere until it actually needs to
- **Medicine reminders** and **daily check-in** through `AlarmScheduler` and dedicated
  broadcast receivers
- **Emergency** — one large button that sends an SMS with a GPS location to saved contacts
- **Health log** — blood pressure records kept locally (`BloodPressureStore`)
- **Photo backup** so a relative can help without handling the phone
- **Weather** from Open-Meteo, plus adzan time and birthday reminders
- **High-contrast theme** and a spoken tutorial on first run

## Architecture

```
app/src/main/java/com/example/nenekmode/
  MainActivity.java          voice UI, Whisper STT, VAD, continuous mode, emergency SMS
  SettingsActivity.java      name, contacts, accessibility, reminders
  IntentMatcher.java         30+ intents from assets/intents.json
  ConversationEngine.java    dialogue state
  GroqClient.java            LLM + Whisper client, context window, Indonesian errors
  GroqStreamingClient.java   streaming variant
  WeatherClient.java         Open-Meteo current + forecast
  CompanionService.java      foreground companion service
  AlarmScheduler.java        medication and check-in alarms
  *Receiver.java             boot, adzan, birthday, check-in, med confirm, escalation
  BloodPressureStore.java    local health records
  BackupUtil.java            photo backup
assets/intents.json           intent patterns and responses
```

## Requirements

- Android 7.0 (API 24) or newer; built against API 34
- A **Groq API key** — entered in Settings, so it never lives in the repository (BYOK)
- Microphone, SMS and location permissions

## Build

```bash
./gradlew assembleDebug
```

Or open the project in Android Studio. Java 8 source level, Gradle plugin 7.4.2.

## Configuration

The assistant is set up entirely on-device through Settings: user's name, favourite contacts
for emergency SMS, accessibility preferences, reminder times, and the Groq key. The model can
be swapped between Groq's Llama variants without touching the code.

## Design notes

- Speech is the primary interface; on-screen elements are large and high contrast.
- Every failure path speaks an Indonesian sentence instead of showing an error code.
- Reminders use `AlarmManager` receivers rather than background polling, so they survive
  Doze.
- The API key is stored in `SharedPreferences` on the device. Anyone with physical access to
  an unlocked phone can read it.

## License

MIT
