/**
 * Bank konten lokal: kuis ringan, doa harian, cerita, nostalgia.
 * Port langsung ContentBank.java (semua embedded, tanpa file tambahan).
 */

const RND = () => Math.random();

function pick<T>(arr: T[]): T {
  return arr[Math.floor(RND() * arr.length)];
}

/** Normalisasi jawaban kuis: huruf kecil, buang tanda baca, rapikan spasi. */
function normalizeAnswer(s: string): string {
  return s
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

// ----- Kuis ringan (stimulasi kognitif): [pertanyaan, ...jawaban benar] -----
const QUIZ: string[][] = [
  ["Hewan apa yang dijuluki 'sahabat petani'?", "sapi"],
  ["Buah apa yang punya nama 'gajah' di dalamnya?", "pepaya"],
  ["Kalau 12 ditambah 13, hasilnya berapa?", "25"],
  ["Ibukota Provinsi Jawa Barat apa?", "bandung"],
  ["Alat musik tradisional dari Jawa Barat apa?", "angklung"],
  ["Hewan apa yang suka makan wortel?", "kelinci"],
  ["Kalau hari ini Senin, besok harinya apa?", "selasa"],
  ["Gunung tertinggi di Indonesia apa?", "puncak jaya"],
  ["Warna bendera Indonesia apa saja?", "merah putih"],
  ["Kalau 5 dikali 8, hasilnya berapa?", "40"],
  ["Pahlawan wanita dari Aceh siapa?", "cut nyak Dien", "cut nyakdhien", "dien"],
  ["Sayur hijau yang bisa dibikin lalapan apa saja?", "bayam"],
];

const DOA_PAGI = [
  "Ya Allah, hari ini aku mohon ketenangan, kesehatan, dan keberkahan. Amin.",
  "Semoga hari ini dipenuhi kebaikan, napas lancar, hati ringan. Bismillah.",
];
const DOA_MALAM = [
  "Selamat tidur, semoga tidurnya nyenyak, dan bangun dalam keadaan sehat. Amin.",
  "Ya Allah, ampuni dosa kami, semoga mimpi buruk menjauh. Selamat beristirahat.",
];
const DOA_SORE = [
  "Semoga sore ini menyenangkan, keluarga di rumah selamat semua.",
  "Terima kasih Tuhan untuk hari ini. Semoga malamnya makin berkah.",
];

const NOSTALGIA_OPENERS = [
  "Kalau boleh tahu, waktu {nenek_name} kecil dulu suka main apa?",
  "Zaman muda dulu, kerjaan {nenek_name} apa? Pasti menarik ceritanya.",
  "Dulu masa muda itu kira-kira mana yang paling {nenek_name} inget?",
  "Waktu anak-anak {nenek_name} kecil dulu, ada kejadian yang lucu nggak?",
  "Dulu lagu apa yang paling sering {nenek_name} dengerin?",
  "Dulu tinggal di rumah seperti apa ya {nenek_name}? Ada pohon buah nggak?",
];

const STORIES = [
  "Ini kisah Nabi Yunus. Beliau pernah di telan ikan besar karena naik kapal saat badai. Di dalam perut ikan, beliau beristighfar tanpa henti. Akhirnya ikan besar itu memuntahkan beliau di pantai, dan beliau selamat. Hikmahnya: jangan pernah putus asa, tobat itu selalu didengar.",
  "Zaman dulu ada kisah Bawang Putih dan Bawang Merah. Bawang Putih yang sabar akhirnya mendapat kebaikan, sedangkan Bawang Merah yang tamak mendapat balasannya. Hikmahnya: kesabaran itu tidak pernah sia-sia ya.",
  "Ada kisah Malin Kundang, anak yang durhaka kepada ibunya. Karena tidak mengakui ibunya, dia dikutuk jadi batu. Hikmahnya: doa ibu itu mustajab, jadi jangan pernah bikin ibu kecewa ya.",
  "Kisah Nabi Ayyub, orang yang paling sabar. Harta, anak, dan kesehatannya diuji berturut-turut, tapi beliau tidak pernah mengeluh, hanya berdoa. Akhirnya semua itu dikembalikan berlipat ganda. Hikmahnya: ujian itu tanda sayang, sabar itu indah.",
  "Ada nasihat tua-tua: kunci hidup tenang di usia senja itu tiga. Makannya kenyang ya, tidurnya cukup ya, dan hatinya jangan di bebani sama masalah orang lain. Sisanya biar Tuhan yang urus.",
  "Dulu orang-orang tua bilang: pagi-pagi itu waktunya paling berkah. Yang bangun pagi, badannya sehat, hatinya senang, rezekinya lancar. Makanya jangan kebanyakan tidur pagi ya.",
  "Ada hikayat tentang pohon beringin. Pohonnya besar, daunnya rindang, siapa saja yang lewat bisa berteduh. Nasihatnya begini: orang yang bermanfaat untuk sekitarnya, namanya akan dikenang selamanya, seperti pohon beringin itu.",
  "Nasihat untuk hari ini: jangan terlalu sering inget yang sudah lewat, jangan juga kuatir yang belum datang. Cukup syukuri hari ini, karena hari ini adalah karunia yang paling nyata.",
];

/** Daftar kuis mentah buat uji regresi (pastikan semua jawaban resmi diterima). */
export function allQuizItems(): ReadonlyArray<readonly string[]> {
  return QUIZ;
}

export const content = {
  getRandomQuizQuestion(): string {
    return pick(QUIZ)[0];
  },

  getQuizAnswer(question: string): string {
    for (const qa of QUIZ) {
      if (qa[0] === question) return qa[1];
    }
    return "";
  },

  checkQuizAnswer(question: string, answer: string): boolean {
    const qa = QUIZ.find((q) => q[0] === question);
    if (!qa) return false;
    const a = normalizeAnswer(answer);
    if (!a) return false;
    const words = new Set(a.split(" "));
    for (let i = 1; i < qa.length; i++) {
      const target = normalizeAnswer(qa[i]);
      if (!target) continue;
      // cocok penuh ("25" di "25", "selasa" di "hari selasa")
      if (a.includes(target)) return true;
      // cocok per kata: "merah dan putih" tetap benar untuk jawaban "merah putih",
      // dan "cut nyak dhien" benar untuk "cut nyak dien"
      const targetWords = target.split(" ");
      if (targetWords.every((w) => words.has(w))) return true;
      // jawaban sebagian tapi kata kuncinya tepat ("puncak", "merah")
      if (targetWords[0].length >= 4 && words.has(targetWords[0])) return true;
    }
    return false;
  },

  getDoa(timeOfDay: string): string {
    if (timeOfDay === "pagi") return pick(DOA_PAGI);
    if (timeOfDay === "sore") return pick(DOA_SORE);
    return pick(DOA_MALAM);
  },

  getRandomNostalgiaOpener(nenekName: string): string {
    return pick(NOSTALGIA_OPENERS).replace(/{nenek_name}/g, nenekName);
  },

  getRandomStory(): string {
    return pick(STORIES);
  },
};
