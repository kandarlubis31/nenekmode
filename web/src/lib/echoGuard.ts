/**
 * Garda gema: menangkap kasus mic merekam suara asisten sendiri (loudspeaker /
 * TTS receiver native) sehingga asisten tidak "menjawab dirinya sendiri".
 * Dipakai useAssistant pada hasil transkrip Whisper.
 */

export function normalizeSpeech(s: string): string {
  return s
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * Deteksi gema: transkrip ternyata hampir sama dengan ucapan asisten terakhir.
 * Sengaja konservatif (frasa panjang / tumpang tindih mayoritas) supaya balasan
 * user yang singkat (mis. "halo", "terima kasih") tetap lolos.
 */
export function looksLikeEcho(transcript: string, lastSpoken: string): boolean {
  if (!lastSpoken) return false;
  const t = normalizeSpeech(transcript);
  const s = normalizeSpeech(lastSpoken);
  if (!t || !s) return false;
  // potongan verbatim yang cukup panjang dari ucapan asisten = pasti gema
  if (t.length >= 12 && s.includes(t)) return true;
  if (s.length >= 12 && t.includes(s)) return true;
  // mayoritas kata transkrip ada di ucapan asisten (>=3 kata)
  const tw = t.split(" ");
  if (tw.length < 3) return false;
  const sw = new Set(s.split(" "));
  const common = tw.filter((w) => sw.has(w)).length;
  return common / tw.length >= 0.8;
}
