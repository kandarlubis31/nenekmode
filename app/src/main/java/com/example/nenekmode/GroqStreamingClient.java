package com.example.nenekmode;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Klien Groq streaming (SSE) + Whisper STT.
 * Streaming: TTS mulai bicara per kalimat tanpa nunggu jawaban penuh.
 * Rate-limit guard: kalau key BYOK kena 429, retry sekali pakai key bawaan.
 */
public class GroqStreamingClient {

    private static final String TAG = "GROQ_STREAM";
    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    private final android.content.Context appContext;

    public GroqStreamingClient(android.content.Context context) {
        appContext = context.getApplicationContext();
    }

    public interface StreamCallback {
        void onSentence(String sentence);
        void onDone(String fullText);
        void onError(String error);
    }

    public void getStreamingResponse(String userText, String nenekName, String asistenName,
                                     String intentsJson, String memoryBlock, List<String> chatHistory,
                                     StreamCallback callback) {
        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", GroqClient.resolveModel(appContext));
            jsonBody.put("stream", true);

            org.json.JSONArray messages = new org.json.JSONArray();

            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            String prompt = "Kamu adalah asisten virtual bernama " + asistenName
                    + " untuk lansia bernama " + nenekName + ".\n"
                    + "Ini database panduanmu dalam format JSON:\n" + intentsJson + "\n\n"
                    + (memoryBlock != null && !memoryBlock.isEmpty() ? memoryBlock + "\n" : "")
                    + "\nAturan mutlak:\n"
                    + "1. Cek apakah ucapan user cocok dengan 'patterns' di JSON.\n"
                    + "2. Jika cocok dan punya 'action' tidak kosong, HANYA balas dengan format: [ACTION:nama_action]\n"
                    + "3. Jika cocok tapi 'action' kosong, balas HANYA dengan salah satu teks di 'responses' JSON tersebut.\n"
                    + "4. Jika TIDAK ADA yang cocok di JSON, jawab bebas secara natural, hangat, merangkul, maksimal 2 kalimat pendek, tanpa simbol/emoji.\n"
                    + "5. Ingat konteks obrolan sebelumnya dengan " + nenekName + ".\n"
                    + "Ganti parameter {nenek_name} dan {assistant_name} dengan nama asli.";
            sys.put("content", prompt);
            messages.put(sys);

            if (chatHistory != null) {
                int start = Math.max(0, chatHistory.size() - 10);
                for (int i = start; i < chatHistory.size(); i++) {
                    String entry = chatHistory.get(i);
                    JSONObject m = new JSONObject();
                    if (entry.startsWith(asistenName + ":")) {
                        m.put("role", "assistant");
                        m.put("content", entry.substring(asistenName.length() + 2).trim());
                    } else if (entry.startsWith("Anda:")) {
                        m.put("role", "user");
                        m.put("content", entry.substring(5).trim());
                    }
                    if (m.has("role")) messages.put(m);
                }
            }

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", userText);
            messages.put(user);

            jsonBody.put("messages", messages);
            jsonBody.put("temperature", 0.5);
            jsonBody.put("max_tokens", 200);

            performStream(jsonBody.toString(), GroqClient.resolveApiKey(appContext), callback, false);
        } catch (Exception e) {
            Log.e(TAG, "Streaming gagal: " + e.getMessage());
            callback.onError("Koneksi gagal. Coba cek internet.");
        }
    }

    /**
     * @param isRetry true kalau ini sudah retry pakai key bawaan (jangan retry lagi).
     */
    private void performStream(String jsonBodyStr, String apiKey, StreamCallback callback, boolean isRetry) {
        final StringBuilder fullText = new StringBuilder();
        Thread t = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Accept", "text/event-stream");

                try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                    out.write(jsonBodyStr.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }

                int code = conn.getResponseCode();
                if (code == 429 && !isRetry) {
                    // Rate-limit guard: sekali retry pakai key bawaan
                    String fallbackKey = GroqClient.keyAfterRateLimit(appContext);
                    if (fallbackKey != null) {
                        Log.w(TAG, "429 pada key aktif, retry pakai key bawaan");
                        performStream(jsonBodyStr, fallbackKey, callback, true);
                        return;
                    }
                }
                if (code != 200) {
                    callback.onError("Server AI sedang bermasalah. Coba lagi nanti.");
                    return;
                }

                // sukses -> clear cooldown BYOK (self-healing)
                if (GroqClient.isByokOnCooldown(appContext)) GroqClient.clearByokCooldown(appContext);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        try {
                            JSONObject chunk = new JSONObject(data);
                            org.json.JSONArray choices = chunk.optJSONArray("choices");
                            if (choices == null || choices.length() == 0) continue;
                            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                            if (delta == null) continue;
                            String piece = delta.optString("content", "");
                            if (piece.isEmpty()) continue;
                            fullText.append(piece);

                            while (true) {
                                int idx = findSentenceBreak(fullText);
                                if (idx < 0) break;
                                String sentence = fullText.substring(0, idx).trim();
                                fullText.delete(0, idx);
                                if (!sentence.isEmpty()) callback.onSentence(sentence);
                            }
                        } catch (Exception ignored) {}
                    }
                }

                String remaining = fullText.toString().trim();
                if (!remaining.isEmpty()) callback.onSentence(remaining);
                callback.onDone(null);

            } catch (Exception e) {
                Log.e(TAG, "Streaming gagal: " + e.getMessage());
                callback.onError("Koneksi gagal. Coba cek internet.");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Cari posisi akhir kalimat. Spasi/baris baru setelah . ! ? dianggap akhir kalimat.
     */
    private static int findSentenceBreak(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                if (i + 1 >= text.length()) return i + 1;
                char next = text.charAt(i + 1);
                if (next == ' ' || next == '\n') return i + 1;
            }
        }
        return -1;
    }

    // ================= Whisper STT =================

    public interface TranscribeCallback {
        void onSuccess(String text);
        void onError(String error);
    }

    public void transcribeAudio(File audioFile, TranscribeCallback callback) {
        performWhisper(audioFile, GroqClient.resolveApiKey(appContext), callback, false);
    }

    private void performWhisper(File audioFile, String apiKey, TranscribeCallback callback, boolean isRetry) {
        Thread t = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String boundary = "----JavaBoundary" + System.currentTimeMillis();
                URL url = new URL("https://api.groq.com/openai/v1/audio/transcriptions");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);

                try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                    writeFormField(out, boundary, "model", "whisper-large-v3");
                    writeFormField(out, boundary, "language", "id");
                    writeFormField(out, boundary, "response_format", "json");
                    writeFileField(out, boundary, "file", audioFile, "audio.wav");
                    out.writeBytes("--" + boundary + "--\r\n");
                    out.flush();
                }

                int code = conn.getResponseCode();
                if (code == 429 && !isRetry) {
                    String fallbackKey = GroqClient.keyAfterRateLimit(appContext);
                    if (fallbackKey != null) {
                        Log.w(TAG, "Whisper 429, retry pakai key bawaan");
                        performWhisper(audioFile, fallbackKey, callback, true);
                        return;
                    }
                }

                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                }

                if (code != 200) {
                    Log.e(TAG, "Whisper error " + code + ": " + sb);
                    callback.onError("Gagal mengenali suara.");
                    return;
                }

                if (GroqClient.isByokOnCooldown(appContext)) GroqClient.clearByokCooldown(appContext);

                JSONObject res = new JSONObject(sb.toString());
                callback.onSuccess(res.getString("text").trim());
            } catch (Exception e) {
                Log.e(TAG, "Whisper gagal: " + e.getMessage());
                callback.onError("Gagal mengenali suara.");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void writeFormField(DataOutputStream out, String boundary, String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.writeBytes(value + "\r\n");
    }

    private static void writeFileField(DataOutputStream out, String boundary, String name, File file, String filename) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n");
        out.writeBytes("Content-Type: audio/wav\r\n\r\n");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.writeBytes("\r\n");
    }

    public static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }
}
