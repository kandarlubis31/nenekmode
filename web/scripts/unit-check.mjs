/**
 * Uji unit ringan (tanpa browser) — Node 22+ bisa impor .ts langsung.
 *
 * Fokus: bank kuis. Dulu jawaban resmi "puncak jaya" ditolak karena salah
 * ketik di data ("punckak jaya"), jadi nenek yang menjawab benar malah
 * dibilang salah.
 *
 * Jalankan: node scripts/unit-check.mjs
 */

import { allQuizItems, content } from "../src/lib/content.ts";

let failed = 0;
const check = (name, ok, detail = "") => {
  if (!ok) failed++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`);
};

const items = allQuizItems();
check("bank kuis tidak kosong", items.length >= 10, `${items.length} pertanyaan`);

for (const qa of items) {
  const question = qa[0];
  const answer = qa[1];
  check(
    `jawaban resmi diterima: "${question.slice(0, 40)}..."`,
    content.checkQuizAnswer(question, answer),
    `jawaban=${answer}`,
  );
}

const GUNUNG = "Gunung tertinggi di Indonesia apa?";
check(
  "soal gunung: 'puncak jaya' dinilai BENAR",
  content.checkQuizAnswer(GUNUNG, "puncak jaya"),
);
check(
  "soal gunung: 'puncak' dinilai BENAR",
  content.checkQuizAnswer(GUNUNG, "Puncak"),
);
check(
  "soal gunung: jawaban salah tetap DITOLAK",
  !content.checkQuizAnswer(GUNUNG, "gunung rinjani"),
);

check(
  "warna bendera: 'merah dan putih' dinilai BENAR",
  content.checkQuizAnswer("Warna bendera Indonesia apa saja?", "merah dan putih"),
);
check(
  "jawaban kosong / tanda baca saja DITOLAK",
  !content.checkQuizAnswer(GUNUNG, "   ...  "),
);

// getQuizAnswer harus konsisten dengan bank
for (const qa of items) {
  check(`getQuizAnswer konsisten: ${qa[0].slice(0, 28)}...`, content.getQuizAnswer(qa[0]) === qa[1]);
}

console.log(`\n${failed === 0 ? "UNIT: ALL PASSED" : `UNIT: ${failed} GAGAL`}`);
process.exit(failed === 0 ? 0 : 1);
