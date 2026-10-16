/**
 * Klien cuaca Open-Meteo (gratis, tanpa API key).
 * Port setia WeatherClient.java.
 */

interface WeatherCallback {
  onSuccess(response: string): void;
  onError(error: string): void;
}

function getWeatherDesc(code: number): string {
  if (code === 0) return "cerah";
  if (code >= 1 && code <= 3) return "agak berawan";
  if (code === 45 || code === 48) return "berkabut";
  if (code >= 51 && code <= 55) return "gerimis ringan";
  if (code >= 61 && code <= 65) return "hujan";
  if (code >= 80 && code <= 82) return "hujan lumayan deras";
  if (code >= 95) return "badai petir";
  return "kurang menentu";
}

async function makeRequest(url: string): Promise<Record<string, unknown>> {
  const resp = await fetch(url, { signal: AbortSignal.timeout(12000) });
  if (!resp.ok) throw new Error("http " + resp.status);
  return (await resp.json()) as Record<string, unknown>;
}

export const weather = {
  async getCurrentWeather(lat: number, lon: number): Promise<string> {
    try {
      const url =
        `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
        `&current=temperature_2m,weather_code&timezone=Asia%2FJakarta`;
      const json = await makeRequest(url);
      const current = json.current as { weather_code: number; temperature_2m: number };
      const desc = getWeatherDesc(current.weather_code);
      const temp = Math.round(current.temperature_2m);
      return `Cuaca di sini sekarang lagi ${desc} dengan suhu sekitar ${temp} derajat celcius.`;
    } catch {
      throw new Error("Maaf, gagal ambil data cuaca. Coba cek internetnya.");
    }
  },

  async getForecast(lat: number, lon: number): Promise<string> {
    try {
      const url =
        `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
        `&daily=weather_code,temperature_2m_max&timezone=Asia%2FJakarta`;
      const json = await makeRequest(url);
      const daily = json.daily as { weather_code: number[]; temperature_2m_max: number[] };
      const code = daily.weather_code[1]; // index 1 = besok
      const temp = Math.round(daily.temperature_2m_max[1]);
      const desc = getWeatherDesc(code);
      return `Perkiraan cuaca buat besok, ${desc} dengan suhu maksimal ${temp} derajat celcius.`;
    } catch {
      throw new Error("Maaf, gagal ambil data cuaca. Coba cek internetnya.");
    }
  },

  /**
   * Peringatan cuaca pagi (sinkron).
   * @returns kalimat peringatan jika hujan/badai, atau "" jika aman/gagal.
   */
  async getWeatherWarningSync(lat: number, lon: number): Promise<string> {
    try {
      const url =
        `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
        `&current=weather_code&timezone=Asia%2FJakarta`;
      const resp = await fetch(url, { signal: AbortSignal.timeout(10000) });
      if (!resp.ok) return "";
      const json = (await resp.json()) as {
        current?: { weather_code?: number };
      };
      const code = json.current?.weather_code ?? 0;
      const rainy = code >= 51 || (code >= 80 && code <= 82) || code >= 95;
      if (!rainy) return "";
      const desc = code >= 95 ? "badai petir" : code >= 80 ? "hujan deras" : "hujan";
      return `Hari ini diprediksi ada ${desc}, kalau mau keluar rumah bawa payung ya, jangan lupa.`;
    } catch {
      return "";
    }
  },
};

export type { WeatherCallback };
