package com.example.nenekmode;

import android.content.Context;

import java.io.InputStream;
import java.util.Random;

/**
 * Bank konten lokal: kuis ringan, doa harian, dan pertanyaan nostalgia.
 * Semua embedded di kode (tanpa file tambahan) supaya gampang dirawat.
 */
public final class ContentBank {

    private final Random random = new Random();

    // ----- Kuis ringan (stimulasi kognitif) -----
    private static final String[][] QUIZ = {
        {"Hewan apa yang dijuluki 'sahabat petani'?", "sapi"},
        {"Buah apa yang punya nama 'gajah' di dalamnya?", "pepaya"},
        {"Kalau 12 ditambah 13, hasilnya berapa?", "25"},
        {"Ibukota Provinsi Jawa Barat apa?", "bandung"},
        {"Alat musik tradisional dari Jawa Barat apa?", "angklung"},
        {"Hewan apa yang suka makan wortel?", "kelinci"},
        {"Kalau hari ini Senin, besok harinya apa?", "selasa"},
        {"Gunung tertinggi di Indonesia apa?", "punckak jaya"},
        {"Warna bendera Indonesia apa saja?", "merah putih"},
        {"Kalau 5 dikali 8, hasilnya berapa?", "40"},
        {"Pahlawan wanita dari Aceh siapa?", "cut nyak Dien", "cut nyakdhien", "dien"},
        {"Sayur hijau yang bisa dibikin lalapan apa saja?", "bayam"},
    };

    // ----- Doa harian (pilih sesuai waktu) -----
    private static final String[][] DOA_PAGI = {
        {"Ya Allah, hari ini aku mohon ketenangan, kesehatan, dan keberkahan. Amin."},
        {"Semoga hari ini dipenuhi kebaikan, napas lancar, hati ringan. Bismillah."},
    };
    private static final String[][] DOA_MALAM = {
        {"Selamat tidur, semoga tidurnya nyenyak, dan bangun dalam keadaan sehat. Amin."},
        {"Ya Allah, ampuni dosa kami, semoga mimpi buruk menjauh. Selamat beristirahat."},
    };
    private static final String[][] DOA_SORE = {
        {"Semoga sore ini menyenangkan, keluarga di rumah selamat semua."},
        {"Terima kasih Tuhan untuk hari ini. Semoga malamnya makin berkah."},
    };

    // ----- Pembuka nostalgia -----
    private static final String[] NOSTALGIA_OPENERS = {
        "Kalau boleh tahu, waktu {nenek_name} kecil dulu suka main apa?",
        "Zaman muda dulu, kerjaan {nenek_name} apa? Pasti menarik ceritanya.",
        "Dulu masa muda itu kira-kira mana yang paling {nenek_name} inget?",
        "Waktu anak-anak {nenek_name} kecil dulu, ada kejadian yang lucu nggak?",
        "Dulu lagu apa yang paling sering {nenek_name} dengerin?",
        "Dulu tinggal di rumah seperti apa ya {nenek_name}? Ada pohon buah nggak?",
    };

    // ----- Cerita & nasihat (dongeng/hikayat/nasihat hidup) -----
    private static final String[] STORIES = {
        "Ini kisah Nabi Yunus. Beliau pernah di telan ikan besar karena naik kapal saat badai. Di dalam perut ikan, beliau beristighfar tanpa henti. Akhirnya ikan besar itu memuntahkan beliau di pantai, dan beliau selamat. Hikmahnya: jangan pernah putus asa, tobat itu selalu didengar.",
        "Zaman dulu ada kisah Bawang Putih dan Bawang Merah. Bawang Putih yang sabar akhirnya mendapat kebaikan, sedangkan Bawang Merah yang tamak mendapat balasannya. Hikmahnya: kesabaran itu tidak pernah sia-sia ya.",
        "Ada kisah Malin Kundang, anak yang durhaka kepada ibunya. Karena tidak mengakui ibunya, dia dikutuk jadi batu. Hikmahnya: doa ibu itu mustajab, jadi jangan pernah bikin ibu kecewa ya.",
        "Kisah Nabi Ayyub, orang yang paling sabar. Harta, anak, dan kesehatannya diuji berturut-turut, tapi beliau tidak pernah mengeluh, hanya berdoa. Akhirnya semua itu dikembalikan berlipat ganda. Hikmahnya: ujian itu tanda sayang, sabar itu indah.",
        "Ada nasihat tua-tua: kunci hidup tenang di usia senja itu tiga. Makannya kenyang ya, tidurnya cukup ya, dan hatinya jangan di bebani sama masalah orang lain. Sisanya biar Tuhan yang urus.",
        "Dulu orang-orang tua bilang: pagi-pagi itu waktunya paling berkah. Yang bangun pagi, badannya sehat, hatinya senang, rezekinya lancar. Makanya jangan kebanyakan tidur pagi ya.",
        "Ada hikayat tentang pohon beringin. Pohonnya besar, daunnya rindang, siapa saja yang lewat bisa berteduh. Nasihatnya begini: orang yang bermanfaat untuk sekitarnya, namanya akan dikenang selamanya, seperti pohon beringin itu.",
        "Nasihat untuk hari ini: jangan terlalu sering inget yang sudah lewat, jangan juga kuatir yang belum datang. Cukup syukuri hari ini, karena hari ini adalah karunia yang paling nyata.",
    };

    public ContentBank() {}

    public String getRandomQuizQuestion() {
        int i = random.nextInt(QUIZ.length);
        return QUIZ[i][0];
    }

    public String getQuizAnswer(String question) {
        for (String[] qa : QUIZ) {
            if (qa[0].equals(question)) return qa[1];
        }
        return "";
    }

    public boolean checkQuizAnswer(String question, String answer) {
        for (String[] qa : QUIZ) {
            if (qa[0].equals(question)) {
                String a = answer.toLowerCase().trim();
                for (int i = 1; i < qa.length; i++) {
                    if (a.contains(qa[i].toLowerCase())) return true;
                }
                return false;
            }
        }
        return false;
    }

    public String getDoa(String timeOfDay) {
        String[][] pool;
        if ("pagi".equals(timeOfDay)) pool = DOA_PAGI;
        else if ("sore".equals(timeOfDay)) pool = DOA_SORE;
        else pool = DOA_MALAM;
        return pool[random.nextInt(pool.length)][0];
    }

    public String getRandomNostalgiaOpener(String nenekName) {
        String s = NOSTALGIA_OPENERS[random.nextInt(NOSTALGIA_OPENERS.length)];
        return s.replace("{nenek_name}", nenekName);
    }

    public String getRandomStory() {
        return STORIES[random.nextInt(STORIES.length)];
    }
}
