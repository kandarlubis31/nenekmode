/**
 * Memori jangka panjang: fakta yang diingat asisten tentang si lansia.
 * Padanan langsung MemoryStore.java (localStorage sebagai backing store).
 */

import { prefs } from "./prefs";

const KEY = "memory_facts";
const MAX_FACTS = 50;

export class MemoryStore {
  add(fact: string | null | undefined): void {
    if (!fact) return;
    const trimmed = fact.trim();
    if (!trimmed || trimmed.length > 200) return;
    const facts = this.getAll();
    if (facts.some((f) => f.toLowerCase() === trimmed.toLowerCase())) return;
    facts.push(trimmed);
    while (facts.length > MAX_FACTS) facts.shift();
    this.save(facts);
  }

  getAll(): string[] {
    const raw = prefs.getString(KEY, "[]");
    try {
      const arr = JSON.parse(raw) as unknown[];
      return arr
        .map((s) => (typeof s === "string" ? s.trim() : ""))
        .filter((s) => s.length > 0);
    } catch {
      return [];
    }
  }

  getPromptBlock(): string {
    const facts = this.getAll();
    if (facts.length === 0) return "";
    return (
      "Fakta yang sudah kamu ketahui tentang dia:\n" +
      facts.map((f) => "- " + f).join("\n") +
      "\n"
    );
  }

  count(): number {
    return this.getAll().length;
  }

  clear(): void {
    this.save([]);
  }

  private save(facts: string[]): void {
    prefs.setString(KEY, JSON.stringify(facts));
  }

  /**
   * Ekstraksi fakta sederhana dari kalimat user.
   * Pola umum yang layak diingat: nama, alergi, punya/ada, suka, tinggal, dll.
   */
  static extractFacts(userText: string | null | undefined): string[] {
    const facts: string[] = [];
    if (!userText) return facts;
    const t = userText.trim();
    if (t.length < 5 || t.length > 180) return facts;
    const lower = t.toLowerCase();

    const patterns = [
      "anak saya", "anakku", "cucu saya", "cucuku",
      "saya alergi", "aku alergi", "alergiku",
      "saya suka", "aku suka", "saya nggak suka", "aku nggak suka",
      "saya tinggal", "aku tinggal", "rumah saya",
      "nama saya", "panggil saya", "saya punya", "aku punya",
      "saya sakit", "saya rutin", "dokter saya",
    ];

    for (const p of patterns) {
      const idx = lower.indexOf(p);
      if (idx >= 0) {
        let fact = t.substring(idx, Math.min(t.length, idx + 120)).trim();
        for (let i = 0; i < fact.length; i++) {
          const c = fact.charAt(i);
          if (c === "." || c === "!" || c === "?" || c === ",") {
            if (i > p.length) fact = fact.substring(0, i);
            break;
          }
        }
        if (fact.length > p.length + 2 && !facts.includes(fact)) {
          facts.push(fact);
        }
      }
    }
    return facts;
  }
}
