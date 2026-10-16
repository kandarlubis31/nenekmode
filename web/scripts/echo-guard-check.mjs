/**
 * Uji garda gema (src/lib/echoGuard.ts): transkrip hasil mic yang ternyata
 * suara asisten sendiri harus diabaikan, balasan user sungguhan harus lolos.
 *
 * Jalankan: node scripts/echo-guard-check.mjs
 */
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const entry = path.join(here, "..", "src", "lib", "echoGuard.ts");

// Node >=22.6: type stripping — impor TS langsung (modul murni tanpa dependensi)
const mod = await import(pathToFileURL(entry).href);
const { looksLikeEcho } = mod;

const GREETING = "Halo nek imah, Kandar sudah siap temani ngobrol nih. Silakan bicara.";
const RESPONSE = "Sama-sama nek imah. Senang bisa membantu.";

const cases = [
  // [transkrip mic, ucapan asisten terakhir, HARUS echo?, keterangan]
  // --- gema yang harus DITANGKAP ---
  ["sama-sama nek imah senang bisa membantu", RESPONSE, true, "gema penuh jawaban TTS"],
  ["sama-sama nek imah senang bisa", RESPONSE, true, "gema potongan jawaban TTS"],
  ["nek imah kandar sudah siap temani ngobrol nih silakan", GREETING, true, "gema sapaan mayoritas kata"],
  ["kandar sudah siap temani ngobrol nih", GREETING, true, "gema tengah kalimat sapaan"],
  // --- input user sungguhan yang harus LOLOS ---
  ["terima kasih", RESPONSE, false, "balasan user pendek"],
  ["halo", GREETING, false, "user membalas sapaan dgn 'halo'"],
  ["halo nek imah apa kabar", GREETING, false, "sapaan user (>3 kata, bukan mayoritas gema)"],
  ["jam berapa sekarang", GREETING, false, "perintah user"],
  ["cuaca hari ini bagaimana", RESPONSE, false, "pertanyaan user"],
  ["terima kasih ya nek imah", RESPONSE, false, "balasan user panjang sedikit tumpang tindih"],
  ["", RESPONSE, false, "transkrip kosong"],
];

let fail = 0;
for (const [t, s, expect, desc] of cases) {
  const got = looksLikeEcho(t, s);
  const ok = got === expect;
  if (!ok) fail++;
  console.log(
    `${ok ? "PASS" : "FAIL"} | ${desc} | "${t}" -> echo=${got} (harus ${expect})`,
  );
}
console.log(fail === 0 ? "\nSEMUA LULUS" : `\nGAGAL: ${fail}`);
process.exit(fail === 0 ? 0 : 1);
