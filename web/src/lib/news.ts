/**
 * Klien RSS berita (detik.com) buat "baca berita".
 * Pakai httpRequest (native HTTP di APK) karena RSS detik tanpa CORS.
 */

import { httpRequest } from "./net";

export interface NewsCallback {
  onSuccess(headlines: string[]): void;
  onError(error: string): void;
}

const FEED_URL = "https://rss.detik.com/index.php/detikcom_nasional";

/** Parsing <item><title>..</title></item> dari RSS sederhana. */
function parseTitles(xml: string): string[] {
  const out: string[] = [];
  const itemRe = /<item[\s\S]*?<\/item>/gi;
  const titleRe = /<title[^>]*>([\s\S]*?)<\/title>/i;
  const items = xml.match(itemRe) ?? [];
  for (const item of items) {
    const m = titleRe.exec(item);
    if (!m) continue;
    const text = m[1]
      .replace(/<!\[CDATA\[|\]\]>/g, "")
      .replace(/<[^>]+>/g, "")
      .replace(/&amp;/g, "&")
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .trim();
    if (text) out.push(text);
  }
  return out;
}

export const news = {
  async getTopHeadlines(): Promise<string[]> {
    const r = await httpRequest({
      url: FEED_URL,
      headers: { "User-Agent": "Mozilla/5.0" },
      timeoutMs: 10000,
    });
    if (r.status !== 200) return [];
    return parseTitles(r.body);
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
