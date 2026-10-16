/**
 * GroqClient: chat completion + Whisper transcribe, versi fetch (web).
 * Port setia GroqClient.java: BYOK resolver, rate-limit guard 429, prompt LLM.
 */

import { prefs } from "./prefs";

const URL_CHAT = "https://api.groq.com/openai/v1/chat/completions";
const URL_WHISPER = "https://api.groq.com/openai/v1/audio/transcriptions";
const URL_MODELS = "https://api.groq.com/openai/v1/models";

/** Key bawaan (di native dari BuildConfig; di web dari env build). */
const BUNDLED_KEY = (import.meta.env.VITE_GROQ_API_KEY as string | undefined) ?? "";

export interface GroqCallback {
  onSuccess(response: string): void;
  onError(error: string): void;
}

export interface TestCallback {
  onResult(ok: boolean, message: string): void;
}

/** BYOK: key user di Pengaturan kalau aktif & valid, else key bawaan. */
export function resolveApiKey(): string {
  const k = prefs.getString("byok_api_key", "").trim();
  const enabled = prefs.getBoolean("byok_enabled", false);
  if (enabled && k.length > 20) return k;
  return BUNDLED_KEY;
}

/** Model chat: bisa dipilih di Pengaturan, default GPT-OSS 20B (cepat). */
export function resolveModel(): string {
  const m = prefs.getString("ai_model", "").trim();
  return m.length > 0 ? m : "openai/gpt-oss-20b";
}

/**
 * Rate-limit guard: request pakai key user (BYOK) kena 429 ->
 * ulangi sekali pakai key bawaan biar asisten nggak pernah diam.
 */
function keyAfterRateLimit(): string | null {
  const byok = prefs.getString("byok_api_key", "").trim();
  const enabled = prefs.getBoolean("byok_enabled", false);
  const usingByok = enabled && byok.length > 0;
  if (usingByok) {
    prefs.setBoolean("byok_cooldown", true);
    return BUNDLED_KEY;
  }
  return null;
}

export function isByokOnCooldown(): boolean {
  return prefs.getBoolean("byok_cooldown", false);
}

export function clearByokCooldown(): void {
  prefs.setBoolean("byok_cooldown", false);
}

/** Tes API key dengan hit /models (murah, tanpa token). */
export async function testApiKey(key: string, cb: TestCallback): Promise<void> {
  try {
    const conn = await fetch(URL_MODELS, {
      headers: { Authorization: "Bearer " + key.trim() },
      signal: AbortSignal.timeout(8000),
    });
    if (conn.status === 200) {
      cb.onResult(true, "API key valid, koneksi berhasil.");
    } else if (conn.status === 401) {
      cb.onResult(false, "API key ditolak (401). Cek lagi ya.");
    } else {
      cb.onResult(false, `Server menjawab ${conn.status}. Coba lagi nanti.`);
    }
  } catch {
    cb.onResult(false, "Koneksi gagal. Cek internet.");
  }
}

function buildSystemPrompt(
  asistenName: string,
  nenekName: string,
  intentsJson: string,
  memoryBlock: string | null,
): string {
  // intentsJson kosong -> deteksi intent sudah ditangani matcher lokal
  // (hemat token: prompt kecil, ramah rate-limit free tier).
  const dbSection = intentsJson
    ? "Ini database panduanmu dalam format JSON:\n" + intentsJson + "\n\n" +
      "Aturan mutlak:\n" +
      "1. Cek apakah ucapan user cocok dengan 'patterns' di JSON.\n" +
      "2. Jika cocok dan punya 'action' tidak kosong, HANYA balas dengan format: [ACTION:nama_action]\n" +
      "3. Jika cocok tapi 'action' kosong, balas HANYA dengan salah satu teks di 'responses' JSON tersebut.\n" +
      "4. Jika TIDAK ADA yang cocok di JSON, "
    :
      "Aturan mutlak:\n Jawab bebas ";
  return (
    "Kamu adalah asisten virtual bernama " + asistenName +
    " untuk lansia bernama " + nenekName + ".\n" +
    (memoryBlock ? memoryBlock + "\n" : "") +
    dbSection +
    "secara natural, hangat, merangkul, maksimal 2 kalimat pendek, tanpa simbol/emoji.\n" +
    "5. Ingat konteks obrolan sebelumnya dengan " + nenekName + ".\n" +
    "Ganti parameter {nenek_name} dan {assistant_name} dengan nama asli."
  );
}

async function makeChatRequest(
  userText: string,
  nenekName: string,
  asistenName: string,
  intentsJson: string,
  memoryBlock: string | null,
  chatHistory: string[] | null,
  callback: GroqCallback,
  keyOverride: string | null,
  fourTwentyNineRetried = false,
): Promise<void> {
  try {
    const messages: Array<{ role: string; content: string }> = [];
    messages.push({
      role: "system",
      content: buildSystemPrompt(asistenName, nenekName, intentsJson, memoryBlock),
    });

    if (chatHistory) {
      const historySize = chatHistory.length;
      const startIdx = Math.max(0, historySize - 10);
      for (let i = startIdx; i < historySize; i++) {
        const entry = chatHistory[i];
        if (entry.startsWith(asistenName + ":")) {
          messages.push({
            role: "assistant",
            content: entry.substring(asistenName.length + 2).trim(),
          });
        } else if (entry.startsWith("Anda:")) {
          messages.push({ role: "user", content: entry.substring(5).trim() });
        }
      }
    }

    messages.push({ role: "user", content: userText });

    const model = resolveModel();
    const body: Record<string, unknown> = {
      model,
      messages,
      temperature: 0.5,
      max_tokens: 300,
    };
    // gpt-oss adalah model reasoning: pakai effort rendah biar jawaban
    // langsung keluar (token gak habis mikir doang).
    if (model.startsWith("openai/gpt-oss")) {
      body.reasoning_effort = "low";
    }

    const resp = await fetch(URL_CHAT, {
      method: "POST",
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        Authorization: "Bearer " + (keyOverride ?? resolveApiKey()),
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(30000),
    });

    const resData = await resp.text();

    if (resp.ok && resData.length > 0) {
      // sukses -> reset cooldown BYOK (self-healing)
      if (isByokOnCooldown()) clearByokCooldown();
      try {
        const jsonRes = JSON.parse(resData);
        const reply: string = jsonRes.choices[0].message.content ?? "";
        if (!reply.trim()) {
          // reasoning model bisa balas kosong -> lempar ke fallback intent
          console.error("GROQ_ERROR: balasan kosong", resData.slice(0, 300));
          callback.onError("Balasan kosong dari sistem pusat.");
          return;
        }
        callback.onSuccess(reply.trim());
      } catch {
        console.error("GROQ_ERROR: Format balasan salah", resData);
        callback.onError("Format data dari pusat tidak sesuai.");
      }
    } else if (resp.status === 429) {
      const fallbackKey =
        keyOverride === null && !isByokOnCooldown() ? keyAfterRateLimit() : null;
      if (fallbackKey) {
        console.warn("GROQ: BYOK kena 429, retry pakai key bawaan");
        await makeChatRequest(
          userText, nenekName, asistenName, intentsJson,
          memoryBlock, chatHistory, callback, fallbackKey,
        );
        return;
      }
      if (keyOverride === null && !fourTwentyNineRetried) {
        // retry sekali setelah jeda singkat (burst rate-limit free tier)
        fourTwentyNineRetried = true;
        await new Promise((r) => setTimeout(r, 5000));
        await makeChatRequest(
          userText, nenekName, asistenName, intentsJson,
          memoryBlock, chatHistory, callback, keyOverride,
        );
        return;
      }
      callback.onError("Terlalu banyak permintaan. Tunggu sebentar.");
    } else if (resp.status >= 500) {
      callback.onError("Server sedang bermasalah. Coba lagi nanti.");
    } else {
      console.error("GROQ_ERROR: Error dari Groq", resData);
      callback.onError("Ada kendala di sistem pusat.");
    }
  } catch {
    callback.onError("Koneksi gagal. Coba cek internet.");
  }
}

export function getResponse(
  userText: string,
  nenekName: string,
  asistenName: string,
  intentsJson: string,
  memoryBlock: string | null,
  chatHistory: string[] | null,
  callback: GroqCallback,
): void {
  void makeChatRequest(
    userText, nenekName, asistenName, intentsJson,
    memoryBlock, chatHistory, callback, null,
  );
}

/** Transkripsi audio (Whisper) — file wav/mp3 dari recorder. */
export async function transcribeAudio(file: File, callback: GroqCallback): Promise<void> {
  try {
    const fd = new FormData();
    fd.append("file", file, file.name || "audio.wav");
    fd.append("model", "whisper-large-v3");
    fd.append("language", "id");
    fd.append("response_format", "json");

    const resp = await fetch(URL_WHISPER, {
      method: "POST",
      headers: { Authorization: "Bearer " + resolveApiKey() },
      body: fd,
      signal: AbortSignal.timeout(30000),
    });
    const resData = await resp.text();

    if (resp.ok && resData.length > 0) {
      try {
        const jsonRes = JSON.parse(resData);
        callback.onSuccess(String(jsonRes.text).trim());
      } catch {
        callback.onError("Format data tidak sesuai.");
      }
    } else if (resp.status === 429) {
      callback.onError("Terlalu banyak permintaan. Tunggu sebentar.");
    } else {
      callback.onError("Gagal mengenali suara.");
    }
  } catch {
    callback.onError("Sistem error saat mengenali suara.");
  }
}
