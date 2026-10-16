/** Probe Groq dari browser (CORS-friendly): status + header ratelimit. */
import puppeteer from "puppeteer-core";

const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const KEY = process.argv[2];

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ["--no-sandbox"],
});
const page = await browser.newPage();
await page.goto("http://localhost:4173/", { waitUntil: "domcontentloaded" });

const result = await page.evaluate(async (key) => {
  const out = [];
  for (let i = 0; i < 3; i++) {
    try {
      const r = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: "Bearer " + key },
        body: JSON.stringify({
          model: "openai/gpt-oss-20b",
          messages: [{ role: "user", content: "halo" }],
          max_tokens: 20,
        }),
      });
      const headers = {};
      r.headers.forEach((v, k) => {
        if (k.includes("ratelimit") || k === "retry-after") headers[k] = v;
      });
      const text = (await r.text()).slice(0, 200);
      out.push({ i, status: r.status, headers, text });
    } catch (e) {
      out.push({ i, error: String(e) });
    }
    await new Promise((r) => setTimeout(r, 3000));
  }
  return out;
}, KEY);

console.log(JSON.stringify(result, null, 2));
await browser.close();
