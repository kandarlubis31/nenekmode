package com.example.nenekmode;

import android.content.Context;
import android.util.Log;
import android.content.SharedPreferences;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GroqClient {

    private final Context appContext;

    /**
     * BYOK: kalau user isi API key sendiri di Pengaturan (byok_api_key), pakai itu.
     * Kalau tidak, fallback ke key yang di-bundle dari local.properties.
     */
    public static String resolveApiKey(Context context) {
        if (context != null) {
            android.content.SharedPreferences p =
                    context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
            String k = p.getString("byok_api_key", "").trim();
            boolean enabled = p.getBoolean("byok_enabled", false);
            if (enabled && !k.isEmpty() && k.length() > 20) return k;
        }
        return BuildConfig.GROQ_API_KEY;
    }

    /** Model chat: bisa dipilih di Pengaturan, default Llama 3.1 8B. */
    public static String resolveModel(Context context) {
        if (context != null) {
            android.content.SharedPreferences p =
                    context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
            String m = p.getString("ai_model", "").trim();
            if (!m.isEmpty()) return m;
        }
        return "openai/gpt-oss-20b";
    }

    /**
     * Rate-limit guard: kalau request pakai key user (BYOK) kena 429,
     * ulangi sekali pakai key bawaan biar asisten nggak pernah diam.
     */
    public static String keyAfterRateLimit(Context context) {
        SharedPreferences p = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        String byok = p.getString("byok_api_key", "").trim();
        boolean enabled = p.getBoolean("byok_enabled", false);
        boolean usingByok = enabled && !byok.isEmpty();
        if (usingByok) {
            p.edit().putBoolean("byok_cooldown", true).apply();
            return BuildConfig.GROQ_API_KEY; // fallback ke key bawaan
        }
        return null; // key bawaan juga kena limit -> biarkan error normal
    }

    public static boolean isByokOnCooldown(Context context) {
        SharedPreferences p = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
        return p.getBoolean("byok_cooldown", false);
    }

    /** Coba lagi nanti (mis. 1 jam) -> clear flag cooldown. */
    public static void clearByokCooldown(Context context) {
        context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE)
                .edit().putBoolean("byok_cooldown", false).apply();
    }

    public interface TestCallback { void onResult(boolean ok, String message); }

    /** Tes API key dengan hit endpoint /models (murah, tanpa token). */
    public static void testApiKey(Context context, String key, TestCallback cb) {
        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.groq.com/openai/v1/models").openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Authorization", "Bearer " + key.trim());
                int code = conn.getResponseCode();
                if (code == 200) {
                    cb.onResult(true, "API key valid, koneksi berhasil.");
                } else if (code == 401) {
                    cb.onResult(false, "API key ditolak (401). Cek lagi ya.");
                } else {
                    cb.onResult(false, "Server menjawab " + code + ". Coba lagi nanti.");
                }
            } catch (Exception e) {
                cb.onResult(false, "Koneksi gagal. Cek internet.");
            }
        }).start();
    }
    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
    private final OkHttpClient client;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public interface GroqCallback {
        void onSuccess(String response);
        void onError(String error);
    }

    public GroqClient(Context context) {
        appContext = context.getApplicationContext();
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public void getResponse(String userText, String nenekName, String asistenName, String intentsJson,
                            String memoryBlock, List<String> chatHistory, GroqCallback callback) {
        makeChatRequest(userText, nenekName, asistenName, intentsJson, memoryBlock,
                chatHistory, callback, null);
    }

    /**
     * @param keyOverride key khusus untuk retry (rate-limit guard); null = pakai resolver normal.
     */
    private void makeChatRequest(String userText, String nenekName, String asistenName, String intentsJson,
                                 String memoryBlock, List<String> chatHistory, GroqCallback callback,
                                 String keyOverride) {
        try {
            JSONObject jsonBody = new JSONObject();
            String modelUsed = resolveModel(appContext);
            jsonBody.put("model", modelUsed);
            if (modelUsed.contains("gpt-oss")) jsonBody.put("reasoning_effort", "low");

            JSONArray messages = new JSONArray();

            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");

            String prompt = "Kamu adalah asisten virtual bernama " + asistenName + " untuk lansia bernama " + nenekName + ".\n"
                    + "Ini database panduanmu dalam format JSON:\n" + intentsJson + "\n\n"
                    + (memoryBlock != null && !memoryBlock.isEmpty() ? memoryBlock + "\n" : "")
                    + "Aturan mutlak:\n"
                    + "1. Cek apakah ucapan user cocok dengan 'patterns' di JSON.\n"
                    + "2. Jika cocok dan punya 'action' tidak kosong, HANYA balas dengan format: [ACTION:nama_action]\n"
                    + "3. Jika cocok tapi 'action' kosong, balas HANYA dengan salah satu teks di 'responses' JSON tersebut.\n"
                    + "4. Jika TIDAK ADA yang cocok di JSON, jawab bebas secara natural, hangat, merangkul, maksimal 2 kalimat pendek, tanpa simbol/emoji.\n"
                    + "5. Ingat konteks obrolan sebelumnya dengan " + nenekName + ".\n"
                    + "Ganti parameter {nenek_name} dan {assistant_name} dengan nama asli.";

            systemMessage.put("content", prompt);
            messages.put(systemMessage);

            if (chatHistory != null) {
                int historySize = chatHistory.size();
                int startIdx = Math.max(0, historySize - 10);
                for (int i = startIdx; i < historySize; i++) {
                    String entry = chatHistory.get(i);
                    JSONObject histMsg = new JSONObject();
                    if (entry.startsWith(asistenName + ":")) {
                        histMsg.put("role", "assistant");
                        histMsg.put("content", entry.substring(asistenName.length() + 2).trim());
                    } else if (entry.startsWith("Anda:")) {
                        histMsg.put("role", "user");
                        histMsg.put("content", entry.substring(5).trim());
                    }
                    if (histMsg.has("role")) {
                        messages.put(histMsg);
                    }
                }
            }

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", userText);
            messages.put(userMessage);

            jsonBody.put("messages", messages);
            jsonBody.put("temperature", 0.5);
            jsonBody.put("max_tokens", 200);

            RequestBody body = RequestBody.create(jsonBody.toString(), JSON);
            Request request = new Request.Builder()
                    .url(URL)
                    .post(body)
                    .addHeader("Authorization", "Bearer " +
                            (keyOverride != null ? keyOverride : resolveApiKey(appContext)))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("GROQ_ERROR", "Koneksi gagal: " + e.getMessage());
                    callback.onError("Koneksi gagal. Coba cek internet.");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String resData = response.body() != null ? response.body().string() : "";

                    if (response.isSuccessful() && !resData.isEmpty()) {
                        // sukses -> reset cooldown BYOK (self-healing)
                        if (isByokOnCooldown(appContext)) clearByokCooldown(appContext);
                        try {
                            JSONObject jsonRes = new JSONObject(resData);
                            String reply = jsonRes.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content");
                            if (reply == null || reply.trim().isEmpty()) {
                                // model reasoning bisa balas kosong -> lempar fallback
                                callback.onError("Balasan kosong dari sistem pusat.");
                                return;
                            }
                            callback.onSuccess(reply.trim());
                        } catch (Exception e) {
                            Log.e("GROQ_ERROR", "Format balasan salah: " + resData);
                            callback.onError("Format data dari pusat tidak sesuai.");
                        }
                    } else if (response.code() == 429) {
                        // Rate-limit guard: coba sekali lagi pakai key bawaan
                        String fallbackKey = (keyOverride == null && !isByokOnCooldown(appContext))
                                ? keyAfterRateLimit(appContext) : null;
                        if (fallbackKey != null) {
                            Log.w("GROQ_ERROR", "BYOK kena 429, retry pakai key bawaan");
                            makeChatRequest(userText, nenekName, asistenName, intentsJson, memoryBlock,
                                    chatHistory, callback, fallbackKey);
                            return;
                        }
                        callback.onError("Terlalu banyak permintaan. Tunggu sebentar.");
                    } else if (response.code() >= 500) {
                        callback.onError("Server sedang bermasalah. Coba lagi nanti.");
                    } else {
                        Log.e("GROQ_ERROR", "Error dari Groq: " + resData);
                        callback.onError("Ada kendala di sistem pusat.");
                    }
                }
            });

        } catch (Exception e) {
            Log.e("GROQ_ERROR", "Sistem error: " + e.getMessage());
            callback.onError("Sistem internal aplikasi error.");
        }
    }

    public void transcribeAudio(File audioFile, GroqCallback callback) {
        try {
            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav",
                            RequestBody.create(audioFile, MediaType.parse("audio/wav")))
                    .addFormDataPart("model", "whisper-large-v3")
                    .addFormDataPart("language", "id")
                    .addFormDataPart("response_format", "json")
                    .build();

            Request request = new Request.Builder()
                    .url(WHISPER_URL)
                    .post(body)
                    .addHeader("Authorization", "Bearer " + resolveApiKey(appContext))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("GROQ_WHISPER", "Whisper gagal: " + e.getMessage());
                    callback.onError("Gagal mengenali suara. Coba ulang.");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String resData = response.body() != null ? response.body().string() : "";

                    if (response.isSuccessful() && !resData.isEmpty()) {
                        try {
                            JSONObject jsonRes = new JSONObject(resData);
                            String text = jsonRes.getString("text");
                            callback.onSuccess(text.trim());
                        } catch (Exception e) {
                            Log.e("GROQ_WHISPER", "Format balasan salah: " + resData);
                            callback.onError("Format data tidak sesuai.");
                        }
                    } else if (response.code() == 429) {
                        callback.onError("Terlalu banyak permintaan. Tunggu sebentar.");
                    } else {
                        Log.e("GROQ_WHISPER", "Error dari Whisper: " + resData);
                        callback.onError("Gagal mengenali suara.");
                    }
                }
            });

        } catch (Exception e) {
            Log.e("GROQ_WHISPER", "Sistem error: " + e.getMessage());
            callback.onError("Sistem error saat mengenali suara.");
        }
    }
}