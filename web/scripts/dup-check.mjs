/**
 * Duplikasi chat check (bug "dia ngespam").
 * Loop feedback: kirim 1 pesan, hitung bubble greeting/user/assistant.
 * PASS kalau masing-masing tepat 1.
 *
 * Jalankan: node scripts/dup-check.mjs [url]
 *   default url: http://localhost:4173/ (preview/produksi)
 *   dev:         node scripts/dup-check.mjs http://localhost:5173/
 */

import puppeteer from "puppeteer-core";

const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const URL = process.argv[2] ?? "http://localhost:4173/";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: [
    "--no-sandbox",
    "--autoplay-policy=no-user-gesture-required",
    "--use-fake-ui-for-media-stream",
    "--use-fake-device-for-media-stream",
  ],
});

const page = await browser.newPage();
await page.setViewport({ width: 412, height: 900 });
page.on("pageerror", (e) => console.log("[pageerror]", e.message));

// lewati wizard: preload localStorage
await page.evaluateOnNewDocument(() => {
  localStorage.setItem(
    "Nenek_Assistant",
    JSON.stringify({ is_first_time: false, nenek_name: "Bu Sari", assistant_name: "Kandar" }),
  );
});

await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
await page.waitForSelector(".status-card", { timeout: 10000 });

// tunggu greeting (delay 1s + TTS)
await sleep(4000);

const count = (needle) =>
  page.evaluate(
    (n) => [...document.querySelectorAll(".chat-box .bubble")].filter((b) => b.textContent.includes(n)).length,
    needle,
  );

const dump = async (label) => {
  const lines = await page.evaluate(() =>
    [...document.querySelectorAll(".chat-box .bubble")].map((b) => b.textContent.trim().slice(0, 90)),
  );
  console.log(`--- ${label} (${lines.length} bubble) ---`);
  lines.forEach((l, i) => console.log(`  ${i}: ${l}`));
};

await dump("setelah greeting");
const greetingN = await count("sudah siap temani");

// kirim tepat SATU pesan
await page.type(".chat-input", "terima kasih.");
await page.keyboard.press("Enter");
await sleep(3500); // matcher lokal = instan
await dump("setelah 1 pesan");
const userN = await count("Anda: terima kasih.");
const asstN = await count("Senang bisa membantu") + (await count("Ada lagi yang bisa dibantu"));

// skenario remount: buka Pengaturan -> kembali (key={tick} remount MainScreen)
await page.evaluate(() => {
  [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Pengaturan"))?.click();
});
await sleep(600);
await page.evaluate(() => {
  [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Kembali"))?.click();
});
await sleep(2500); // greeting remount jalan (delay 1s)
await dump("setelah remount settings");
const greetingN2 = await count("sudah siap temani");
const userN2 = await count("Anda: terima kasih.");
const asstN2 = await count("Senang bisa membantu") + (await count("Ada lagi yang bisa dibantu"));

console.log("\n=== HASIL ===");
console.log(`greeting : ${greetingN} -> ${greetingN2}`);
console.log(`user msg : ${userN} -> ${userN2}`);
console.log(`asisten  : ${asstN} -> ${asstN2}`);

const liveDup = userN > 1 || asstN > 1 || greetingN > 1;
const remountDup = greetingN2 > greetingN || userN2 > userN || asstN2 > asstN;

console.log(`\nlive dup (StrictMode/loop): ${liveDup ? "YA" : "tidak"}`);
console.log(`remount dup (greeting tiap mount): ${remountDup ? "YA" : "tidak"}`);

await browser.close();
process.exit(liveDup || remountDup ? 1 : 0);
