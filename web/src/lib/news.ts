/**
 * Klien berita buat "baca berita".
 *
 * Sumber 1: RSS detik.com lewat httpRequest (native HTTP di APK, karena feed
 * ini tidak mengirim header CORS -> di browser biasa fetch selalu gagal).
 * Sumber 2 (fallback, CORS-friendly): berita-indo-api (CNN Indonesia).
 * Di browser sumber 1 gagal dan sumber 2 yang dipakai, jadi fitur berita
 * tetap jalan di versi website.
 */

import { Capacitor } from "@capacitor/core";
import { httpRequest } from "./net";

export interface NewsCallback {
  onSuccess(headlines: string[]): void;
  onError(error: string): void;
}

const FEED_URL = "https://rss.detik.com/index.php/detikcom_nasional";
const FALLBACK_URL = "https://berita-indo-api.vercel.app/v1/cnn-news/";

/** Judul yang tidak enak dibacakan TTS (mis. "VIDEO: ...") dibuang. */
const UNSPEAKABLE = /^(video|foto|infografis|live)\b[:\s-]*/i;

function cleanTitle(raw: string): string {
  return raw.replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim();
}

/** Parsing <item><title>..</title></item> dari RSS sederhana. */
function parseTitles(xml: string): string[] {
  const out: string[] = [];
  const itemRe = /<item[\s\S]*?<\/item>/gi;
  const titleRe = /<title[^>]*>([\s\S]*?)<\/title>/i;
  const items = xml.match(itemRe) ?? [];
  for (const item of items) {
    const m = titleRe.exec(item);
    if (!m) continue;
    const text = cleanTitle(
      m[1]
        .replace(/<!\[CDATA\[|\]\]>/g, "")
        .replace(/&amp;/g, "&")
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">"),
    );
    if (text) out.push(text);
  }
  return out;
}

/** Ambil judul dari API JSON CNN Indonesia (dipakai kalau RSS diblokir CORS). */
function parseJsonTitles(body: string): string[] {
  try {
    const json = JSON.parse(body) as { data?: Array<{ title?: string }> };
    const out: string[] = [];
    for (const item of json.data ?? []) {
      const text = cleanTitle(item?.title ?? "");
      if (text) out.push(text);
    }
    return out;
  } catch {
    return [];
  }
}

/** Buang judul tak layak dibacakan + duplikat, maksimal `limit`. */
function tidying(titles: string[], limit = 5): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const t of titles) {
    if (!t || t.length < 12 || t.length > 220) continue;
    if (UNSPEAKABLE.test(t)) continue;
    const key = t.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(t);
    if (out.length >= limit) break;
  }
  return out;
}

export const news = {
  async getTopHeadlines(): Promise<string[]> {
    // RSS detik hanya lewat native HTTP (di browser selalu diblokir CORS,
    // dan percobaan itu menumpuk error merah di konsol) -> langsung lompat.
    if (Capacitor.isNativePlatform()) {
      try {
        const r = await httpRequest({
          url: FEED_URL,
          headers: { "User-Agent": "Mozilla/5.0" },
          timeoutMs: 8000,
        });
        if (r.ok) {
          const titles = tidying(parseTitles(r.body));
          if (titles.length > 0) return titles;
        }
      } catch {
        /* lanjut ke sumber kedua */
      }
    }

    // Fallback CORS-friendly (website / browser).
    try {
      const r = await httpRequest({ url: FALLBACK_URL, timeoutMs: 8000 });
      if (r.ok) return tidying(parseJsonTitles(r.body));
    } catch {
      /* tidak ada sumber yang bisa dipakai */
    }
    return [];
  },

  /** Versi ber-callback biar mirip pemakaian di Java. */
  getTopHeadlinesCb(cb: NewsCallback): void {
    news
      .getTopHeadlines()
      .then((headlines) => {
        if (headlines.length === 0) {
          cb.onError("Daftar beritanya belum tersedia.");
        } else {
          cb.onSuccess(headlines);
        }
      })
      .catch(() => cb.onError("Gagal mengambil berita. Cek internetnya."));
  },
};
