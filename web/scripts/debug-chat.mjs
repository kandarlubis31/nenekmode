/** Debug: kirim chat bebas, dump bubble + respons Groq. */
import puppeteer from "puppeteer-core";

const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ["--no-sandbox", "--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"],
});
const page = await browser.newPage();
await page.setViewport({ width: 412, height: 900 });

page.on("response", async (r) => {
  if (r.url().includes("api.groq.com")) {
    let body = "";
    try {
      body = (await r.text()).slice(0, 400);
    } catch {
      /* abaikan */
    }
    console.log("[groq]", r.status(), body);
  }
});
page.on("console", (m) => console.log("[console]", m.text().slice(0, 250)));

await page.goto("http://localhost:4173/", { waitUntil: "networkidle2" });
await page.waitForSelector(".wizard", { timeout: 10000 });
await page.evaluate(() => {
  [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Lewati"))?.click();
});
await page.waitForSelector(".status-card", { timeout: 10000 });
await sleep(2500);

const msg = process.argv[2] ?? "pentingnya minum air buat orang tua itu apa ya";
await page.type(".chat-input", msg);
await page.keyboard.press("Enter");
await sleep(20000);

const bubbles = await page.evaluate(() =>
  [...document.querySelectorAll(".bubble")].map((b) => b.textContent.slice(0, 180)),
);
console.log("=== BUBBLES ===");
for (const b of bubbles) console.log(" •", b);

await browser.close();
