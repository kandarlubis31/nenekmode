package com.example.nenekmode;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Memori jangka panjang: fakta yang diingat asisten tentang si lansia
 * (nama anak, alergi, kebiasaan, dll). Disimpan di SharedPreferences sebagai JSON.
 * Dipakai buat mode nostalgia & konteks obrolan.
 */
public class MemoryStore {

    private static final String KEY = "memory_facts";
    private static final int MAX_FACTS = 50;

    private final SharedPreferences prefs;

    public MemoryStore(Context context) {
        prefs = context.getSharedPreferences("Nenek_Assistant", Context.MODE_PRIVATE);
    }

    public synchronized void add(String fact) {
        if (fact == null) return;
        fact = fact.trim();
        if (fact.isEmpty() || fact.length() > 200) return;
        List<String> facts = getAll();
        // hindari duplikat persis
        for (String f : facts) {
            if (f.equalsIgnoreCase(fact)) return;
        }
        facts.add(fact);
        while (facts.size() > MAX_FACTS) facts.remove(0);
        save(facts);
    }

    public synchronized List<String> getAll() {
        List<String> out = new ArrayList<>();
        String raw = prefs.getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized String getPromptBlock() {
        List<String> facts = getAll();
        if (facts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Fakta yang sudah kamu ketahui tentang dia:\n");
        for (String f : facts) sb.append("- ").append(f).append("\n");
        return sb.toString();
    }

    public synchronized int count() {
        return getAll().size();
    }

    public synchronized void clear() {
        save(new ArrayList<String>());
    }

    private void save(List<String> facts) {
        try {
            JSONArray arr = new JSONArray();
            for (String f : facts) arr.put(f);
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Ekstraksi fakta sederhana dari kalimat user.
     * Pola umum yang layak diingat: nama, alergi, punya/ada, suka, tinggal, dll.
     */
    public static List<String> extractFacts(String userText) {
        List<String> facts = new ArrayList<>();
        if (userText == null) return facts;
        String t = userText.trim();
        if (t.length() < 5 || t.length() > 180) return facts;
        String lower = t.toLowerCase();

        String[] patterns = {
                "anak saya", "anakku", "cucu saya", "cucuku",
                "saya alergi", "aku alergi", "alergiku",
                "saya suka", "aku suka", "saya nggak suka", "aku nggak suka",
                "saya tinggal", "aku tinggal", "rumah saya",
                "nama saya", "panggil saya", "saya punya", "aku punya",
                "saya sakit", "saya rutin", "dokter saya"
        };
        for (String p : patterns) {
            int idx = lower.indexOf(p);
            if (idx >= 0) {
                // ambil dari awal pola sampai akhir kalimat (max 120 char)
                int start = idx;
                int end = Math.min(t.length(), idx + 120);
                String fact = t.substring(start, end).trim();
                // potong di akhir kalimat jika ada
                for (int i = 0; i < fact.length(); i++) {
                    char c = fact.charAt(i);
                    if (c == '.' || c == '!' || c == '?' || c == ',') {
                        if (i > p.length()) { fact = fact.substring(0, i); }
                        break;
                    }
                }
                if (fact.length() > p.length() + 2 && !facts.contains(fact)) {
                    facts.add(fact);
                }
            }
        }
        return facts;
    }
}
