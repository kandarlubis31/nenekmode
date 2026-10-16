/**
 * Smoke test UI NenekMode Web via puppeteer-core + Chrome sistem.
 * Jalankan: node scripts/smoke.mjs (server preview harus jalan di :4173)
 */

import puppeteer from "puppeteer-core";
import { mkdirSync } from "node:fs";

const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const URL = "http://localhost:4173/";
mkdirSync("smoke", { recursive: true });

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

let failed = false;
const step = (name, ok) => {
  console.log(`${ok ? "✅" : "❌"} ${name}`);
  if (!ok) failed = true;
};

try {
  const page = await browser.newPage();
  await page.setViewport({ width: 412, height: 900 });
  page.on("pageerror", (e) => console.log("[pageerror]", e.message));
  page.on("console", (m) => {
    if (m.type() === "error") console.log("[browser-error]", m.text().slice(0, 200));
  });

  await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });

  // 1. Wizard tampil (is_first_time default true)
  await page.waitForSelector(".wizard", { timeout: 10000 });
  step("wizard tampil", true);
  await page.screenshot({ path: "smoke/1-wizard.png" });

  // isi wizard 4 langkah sampai selesai
  await page.type('input[type="text"]', "Bu Sari"); // nama nenek
  const next = async () => {
    await page.evaluate(() => {
      [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Lanjut"))?.click();
    });
    await sleep(300);
  };
  await next(); // -> langkah 2
  await next(); // -> langkah 3
  await next(); // -> langkah 4 (ringkasan)
  const summaryOk = await page.evaluate(() =>
    document.querySelector(".summary")?.textContent?.includes("Bu Sari"),
  );
  step("wizard ringkasan berisi nama", !!summaryOk);
  await page.evaluate(() => {
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Mulai Ngobrol"))?.click();
  });

  // 2. Layar utama
  await page.waitForSelector(".status-card", { timeout: 10000 });
  step("layar utama tampil", true);
  await sleep(2500); // greeting delay 1s + TTS
  const greetingOk = await page.evaluate(() =>
    [...document.querySelectorAll(".bubble.assistant")].some((b) => b.textContent.includes("Bu Sari")),
  );
  step("sapaan awal menyebut nama", !!greetingOk);
  await page.screenshot({ path: "smoke/2-main.png" });

  // 3. Chat LLM: tanya jam -> [ACTION:get_time]
  await page.type(".chat-input", "jam berapa sekarang");
  await page.keyboard.press("Enter");
  await page.waitForFunction(
    () =>
      [...document.querySelectorAll(".bubble.assistant")].some((b) =>
        b.textContent.includes("Sekarang jam"),
      ),
    { timeout: 60000 },
  );
  step("chat LLM -> action get_time", true);
  await page.screenshot({ path: "smoke/3-chat.png" });
  await sleep(8000); // jeda biar kena rate limit Groq free tier

  // 3b. Chat bebas (harus melahirkan bubble asisten baru dari LLM)
  const beforeCount = await page.evaluate(
    () => document.querySelectorAll(".bubble.assistant").length,
  );
  await page.type(".chat-input", "pentingnya minum air buat orang tua itu apa ya");
  await page.keyboard.press("Enter");
  await page.waitForFunction(
    (n) => document.querySelectorAll(".bubble.assistant").length > n,
    { timeout: 60000 },
    beforeCount,
  );
  const freeReply = await page.evaluate(() => {
    const all = [...document.querySelectorAll(".bubble.assistant")];
    return all[all.length - 1]?.textContent ?? "";
  });
  console.log("   jawaban bebas:", freeReply.slice(0, 140));
  step("chat bebas LLM natural", !freeReply.includes("[ACTION") && freeReply.length > 20);

  // 4. Quick command lokal: mulai kuis
  await page.type(".chat-input", "mulai kuis");
  await page.keyboard.press("Enter");
  await page.waitForFunction(
    () =>
      [...document.querySelectorAll(".bubble.assistant")].some((b) =>
        b.textContent.includes("kuis ringan"),
      ),
    { timeout: 20000 },
  );
  step("quick command kuis", true);

  // jawab kuis (kalau jawaban ada di pertanyaan, uji konfirmasi)
  const quizText = await page.evaluate(
    () =>
      [...document.querySelectorAll(".bubble.assistant")]
        .reverse()
        .find((b) => b.textContent.includes("kuis ringan"))?.textContent ?? "",
  );
  console.log("   pertanyaan kuis:", quizText.slice(0, 120));

  // 5. Tombol kesehatan
  await page.evaluate(() => {
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Kesehatan"))?.click();
  });
  await page.waitForFunction(
    () =>
      [...document.querySelectorAll(".bubble.assistant")].some((b) =>
        b.textContent.includes("Jadwal obat"),
      ),
    { timeout: 10000 },
  );
  step("tombol kesehatan", true);

  // 6. Pengaturan
  await page.evaluate(() => {
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Pengaturan"))?.click();
  });
  await page.waitForSelector(".tabs", { timeout: 5000 });
  step("layar pengaturan tampil", true);
  await page.screenshot({ path: "smoke/4-settings.png" });

  // tab Teknis
  await page.evaluate(() => {
    [...document.querySelectorAll(".tab")].find((b) => b.textContent.includes("Mode Teman"))?.click();
  });
  await sleep(400);
  await page.screenshot({ path: "smoke/5-settings-teknis.png" });

  // 7. Papan info
  await page.evaluate(() => {
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Kembali"))?.click();
  });
  await sleep(500);
  await page.evaluate(() => {
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Papan Info"))?.click();
  });
  await page.waitForSelector(".board .clock", { timeout: 5000 });
  step("papan info tampil", true);
  await page.screenshot({ path: "smoke/6-board.png" });
} catch (e) {
  failed = true;
  console.log("❌ crash:", e.message);
}

await browser.close();
console.log(failed ? "SMOKE: ADA GAGAL" : "SMOKE: ALL PASSED");
process.exit(failed ? 1 : 0);
