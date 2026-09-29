package com.example.nenekmode;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import java.io.IOException;

public class WeatherClient {

    private final OkHttpClient client;

    public interface WeatherCallback {
        void onSuccess(String response);
        void onError(String error);
    }

    public WeatherClient() {
        client = new OkHttpClient();
    }

    public void getCurrentWeather(double lat, double lon, WeatherCallback callback) {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current=temperature_2m,weather_code&timezone=Asia%2FJakarta";
        makeRequest(url, responseStr -> {
            JSONObject json = new JSONObject(responseStr);
            JSONObject current = json.getJSONObject("current");
            int code = current.getInt("weather_code");
            int temp = (int) current.getDouble("temperature_2m");
            String desc = getWeatherDesc(code);
            callback.onSuccess("Cuaca di sini sekarang lagi " + desc + " dengan suhu sekitar " + temp + " derajat celcius.");
        }, callback);
    }

    public void getForecast(double lat, double lon, WeatherCallback callback) {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&daily=weather_code,temperature_2m_max&timezone=Asia%2FJakarta";
        makeRequest(url, responseStr -> {
            JSONObject json = new JSONObject(responseStr);
            JSONObject daily = json.getJSONObject("daily");
            int code = daily.getJSONArray("weather_code").getInt(1); // Index 1 untuk besok
            int temp = (int) daily.getJSONArray("temperature_2m_max").getDouble(1);
            String desc = getWeatherDesc(code);
            callback.onSuccess("Perkiraan cuaca buat besok, " + desc + " dengan suhu maksimal " + temp + " derajat celcius.");
        }, callback);
    }

    private void makeRequest(String url, ResponseHandler handler, WeatherCallback callback) {
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Maaf, gagal ambil data cuaca. Coba cek internetnya.");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        handler.handle(response.body().string());
                    } catch (Exception e) {
                        callback.onError("Data cuacanya lagi bermasalah nih.");
                    }
                } else {
                    callback.onError("Sistem cuacanya lagi sibuk.");
                }
            }
        });
    }

    private String getWeatherDesc(int code) {
        if (code == 0) return "cerah";
        if (code >= 1 && code <= 3) return "agak berawan";
        if (code == 45 || code == 48) return "berkabut";
        if (code >= 51 && code <= 55) return "gerimis ringan";
        if (code >= 61 && code <= 65) return "hujan";
        if (code >= 80 && code <= 82) return "hujan lumayan deras";
        if (code >= 95) return "badai petir";
        return "kurang menentu";
    }

    /**
     * Peringatan cuaca pagi (sinkron, panggil dari background thread).
     * @return kalimat peringatan jika hujan/badai, atau "" jika aman/gagal.
     */
    public static String getWeatherWarningSync(double lat, double lon) {
        try {
            java.net.URL url = new java.net.URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                            + "&current=weather_code&timezone=Asia%2FJakarta");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return "";
            StringBuilder sb = new StringBuilder();
            try (java.io.InputStream in = conn.getInputStream();
                 java.io.BufferedReader r = new java.io.BufferedReader(
                         new java.io.InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            int code = json.getJSONObject("current").getInt("weather_code");
            boolean rainy = code >= 51 || (code >= 80 && code <= 82) || code >= 95;
            if (!rainy) return "";
            String desc = code >= 95 ? "badai petir"
                    : (code >= 80 ? "hujan deras" : "hujan");
            return "Hari ini diprediksi ada " + desc + ", kalau mau keluar rumah bawa payung ya, jangan lupa.";
        } catch (Exception e) {
            return "";
        }
    }

    private interface ResponseHandler {
        void handle(String responseStr) throws Exception;
    }
}