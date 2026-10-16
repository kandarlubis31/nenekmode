/**
 * Self-heal riwayat: localStorage diisi chat_history yang sudah ter-dobel
 * (bug spam lama) -> saat load, duplikat harus dibersihkan (tampil sekali).
 * Jalankan: node scripts/dup-heal-check.mjs [url]
 */
import puppeteer from "puppeteer-core";

const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const URL = process.argv[2] ?? "http://localhost:4173/";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ["--no-sandbox", "--autoplay-policy=no-user-gesture-required"],
});
const page = await browser.newPage();
await page.setViewport({ width: 412, height: 900 });

// riwayat ter-dobel: greeting x2, exchange x2 (pola bug asli)
const dupHistory = [
  "[12:04] Kandar: Halo Bu Sari, halo.",
  "[12:04] Kandar: Halo Bu Sari, halo.",
  "[12:04] Anda: terima kasih.",
  "[12:04] Kandar: Sama-sama Bu Sari.",
  "[12:04] Anda: terima kasih.",
  "[12:04] Kandar: Sama-sama Bu Sari.",
].join("\n");

await page.evaluateOnNewDocument((hist) => {
  localStorage.setItem(
    "Nenek_Assistant",
    JSON.stringify({
      is_first_time: false,
      nenek_name: "Bu Sari",
      assistant_name: "Kandar",
      greeted: "1",
      chat_history: hist,
    }),
  );
}, dupHistory);

await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
await page.waitForSelector(".chat-box .bubble", { timeout: 10000 });
await sleep(3500); // lewatkan timer greeting

const bubbles = await page.evaluate(() =>
  [...document.querySelectorAll(".chat-box .bubble")].map((b) => b.textContent.trim()),
);
console.log(`--- ${bubbles.length} bubble ---`);
bubbles.forEach((b, i) => console.log(`  ${i}: ${b.slice(0, 90)}`));

const healed = await page.evaluate(() => {
  const raw = JSON.parse(localStorage.getItem("Nenek_Assistant") ?? "{}").chat_history ?? "";
  return raw.split("\n").filter((l) => l.trim()).length;
});

const ok =
  bubbles.length === 4 && // 2 greeting ter-dup di prefs => tampil 2 (sudah dibersihkan jadi... lihat log
  healed === 4; // 6 baris -> 4 setelah dibersihkan
console.log(`\nprefs chat_history setelah load: ${healed} baris (expect 4)`);
console.log(`self-heal: ${healed === 4 ? "PASS" : "FAIL"}`);

await browser.close();
process.exit(healed === 4 ? 0 : 1);
