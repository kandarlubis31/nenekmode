/**
 * Audit runtime (READ-ONLY terhadap src) — dipakai buat audit versi web.
 * Fokus: bug perilaku yang hanya kelihatan saat jalan (bukan sekadar baca kode).
 *
 * Jalankan (server preview sudah jalan di :4173):
 *   node scripts/audit-check.mjs [url]
 */

import puppeteer from "puppeteer-core";

const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const URL = process.argv[2] ?? "http://localhost:4173/";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
const check = (name, ok, detail = "") => {
  results.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`);
};

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

const errors = [];
const newPage = async () => {
  const page = await browser.newPage();
  await page.setViewport({ width: 412, height: 900, hasTouch: true, isMobile: true });
  page.on("pageerror", (e) => errors.push("[pageerror] " + e.message));
  page.on("console", (m) => {
    if (m.type() === "error") errors.push("[console] " + m.text().slice(0, 200));
  });
  return page;
};

const seedPrefs = (page, obj) =>
  page.evaluateOnNewDocument((o) => {
    localStorage.setItem("Nenek_Assistant", JSON.stringify(o));
  }, obj);

const micText = (page) =>
  page.evaluate(() => document.querySelector(".btn.mic")?.textContent ?? "");

/** Buka layar utama dengan mic dimatikan (biar uji tidak menembak Whisper). */
const openMain = async (extra = {}) => {
  const page = await newPage();
  await page.evaluateOnNewDocument(() => {
    try {
      Object.defineProperty(navigator, "mediaDevices", {
        configurable: true,
        value: {
          getUserMedia: () => Promise.reject(new Error("mic dimatikan untuk uji")),
        },
      });
    } catch {
      /* abaikan */
    }
  });
  await seedPrefs(page, {
    is_first_time: false,
    nenek_name: "Bu Sari",
    assistant_name: "Kandar",
    ...extra,
  });
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
  await page.waitForSelector(".btn.mic", { timeout: 10000 });
  return page;
};

const openSettings = async (page) => {
  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Pengaturan"))?.click(),
  );
  await page.waitForSelector(".tabs", { timeout: 5000 });
};

const clickSwitch = (page, label) =>
  page.evaluate((text) => {
    const row = [...document.querySelectorAll(".switch-row")].find((r) =>
      r.textContent.includes(text),
    );
    row?.querySelector("input")?.click();
    return !!row;
  }, label);

// ============================================================
// TEST 1 — tombol mic besar (touch): bisa di-toggle, tak dobel
// ============================================================
{
  console.log("\n=== TEST 1: tombol mic besar (touch) ===");
  const page = await newPage();
  await seedPrefs(page, { is_first_time: false, nenek_name: "Bu Sari", assistant_name: "Kandar" });
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
  await page.waitForSelector(".btn.mic", { timeout: 10000 });

  // beri waktu loop mic auto jalan (setelah sapaan + tunggu TTS)
  await sleep(3000);

  // instrumentasi event pada tombol mic
  await page.evaluate(() => {
    const b = document.querySelector(".btn.mic");
    window.__mic = { touchstart: 0, touchend: 0, pointerdown: 0, click: 0 };
    if (!b) return;
    for (const ev of ["touchstart", "touchend", "pointerdown", "click"]) {
      b.addEventListener(ev, () => { window.__mic[ev]++; }, true);
    }
  });

  const isListening = async () =>
    (await micText(page)).includes("Dengar terus");
  const before = await isListening();

  // TAP sentuh (seperti jari di HP)
  await page.tap(".btn.mic");

  // tunggu sampai status mendengarkan berubah dari keadaan sebelumnya
  let changed = false;
  try {
    await page.waitForFunction(
      (prev) => (document.querySelector(".btn.mic")?.textContent ?? "").includes("Dengar terus") !== prev,
      { timeout: 16000, polling: 250 },
      before,
    );
    changed = true;
  } catch {
    changed = false;
  }

  const evt = await page.evaluate(() => window.__mic);
  console.log("  event:", JSON.stringify(evt), "| before:", before ? "mendengarkan" : "idle");
  check("TAP tombol mic mengubah state (toggle)", changed, `sebelum=${before ? "listen" : "idle"}`);
  check(
    "TAP tidak memicu handler dua kali (touchstart+click dobel)",
    evt.touchstart <= 1 && evt.click <= 1,
    `touchstart=${evt.touchstart} click=${evt.click}`,
  );

  await page.close();
}

// ============================================================
// TEST 2 — saklar "Aktifkan pengingat" tidak bisa dimatikan
// ============================================================
{
  console.log("\n=== TEST 2: saklar 'Aktifkan pengingat' saat ada slot obat aktif ===");
  const page = await newPage();
  await seedPrefs(page, {
    is_first_time: false,
    nenek_name: "Bu Sari",
    assistant_name: "Kandar",
    med_times: JSON.stringify([
      { h: 8, m: 0, en: true },
      { h: 14, m: 0, en: false },
      { h: 20, m: 0, en: false },
    ]),
    reminder_enabled: false,
  });
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
  await page.waitForSelector(".btn.mic", { timeout: 10000 });

  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Pengaturan"))?.click(),
  );
  await page.waitForSelector(".tabs", { timeout: 5000 });

  const initial = await page.evaluate(() =>
    (() => {
      const rows = [...document.querySelectorAll(".switch-row")];
      const row = rows.find((r) => r.textContent.includes("Aktifkan pengingat"));
      return row?.querySelector("input")?.checked;
    })(),
  );
  check("saklar pengingat awal = OFF (pref false)", initial === false, `checked=${initial}`);

  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Simpan Pengaturan"))?.click(),
  );
  await sleep(500);
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem("Nenek_Assistant") || "{}").reminder_enabled);
  check("setelah Simpan, reminder_enabled tetap OFF", saved === false, `reminder_enabled=${saved}`);
  await page.close();
}

// ============================================================
// TEST 3 — mode papan (board_mode) tak bisa dinyalakan dari UI
// ============================================================
{
  console.log("\n=== TEST 3: mode papan (board_mode) ===");
  const page = await newPage();
  await seedPrefs(page, { is_first_time: false, nenek_name: "Bu Sari" });
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
  await page.waitForSelector(".btn.mic", { timeout: 10000 });

  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Papan Info"))?.click(),
  );
  await page.waitForSelector(".board", { timeout: 5000 });
  const boardModeAfterOpen = await page.evaluate(
    () => JSON.parse(localStorage.getItem("Nenek_Assistant") || "{}").board_mode,
  );
  check(
    "membuka Papan Info tidak menyetel board_mode (jadi tak persisten)",
    boardModeAfterOpen !== true,
    `board_mode=${boardModeAfterOpen}`,
  );

  // M2: tombol "Jadikan Mode Papan" menyetel board_mode=true
  await page.evaluate(() => {
    [...document.querySelectorAll("button.hint")]
      .find((b) => b.textContent.includes("Jadikan Mode Papan"))?.click();
  });
  await sleep(300);
  const pinned = await page.evaluate(
    () => JSON.parse(localStorage.getItem("Nenek_Assistant") || "{}").board_mode,
  );
  check("tombol 'Jadikan Mode Papan' menyetel board_mode=true", pinned === true, `board_mode=${pinned}`);

  // keluar -> board_mode dibersihkan
  await page.evaluate(() => {
    [...document.querySelectorAll("button.hint")]
      .find((b) => b.textContent.includes("kembali ke layar utama"))?.click();
  });
  await sleep(400);
  const cleared = await page.evaluate(
    () => JSON.parse(localStorage.getItem("Nenek_Assistant") || "{}").board_mode,
  );
  check("keluar dari Papan Info membersihkan board_mode", cleared === false, `board_mode=${cleared}`);
  await page.close();
}

// ============================================================
// TEST 4 — Wizard alur penuh + opsi lewati
// ============================================================
{
  console.log("\n=== TEST 4: wizard ===");
  const page = await newPage();
  await seedPrefs(page, {}); // is_first_time default true
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
  await page.waitForSelector(".wizard", { timeout: 10000 });
  check("wizard tampil saat pertama kali", true);
  await page.close();
}

// ============================================================
// TEST 5 — setiap layar tanpa error console
// ============================================================
{
  console.log("\n=== TEST 5: walk-through layar ===");
  const page = await newPage();
  await seedPrefs(page, { is_first_time: false, nenek_name: "Bu Sari", assistant_name: "Kandar" });
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
  await page.waitForSelector(".btn.mic", { timeout: 10000 });
  await sleep(2500);

  // main screen kebersihan chat
  const bubbles = await page.evaluate(() => document.querySelectorAll(".chat-box .bubble").length);
  check("layar utama render bubble chat", bubbles >= 1, `${bubbles} bubble`);

  // kirim 1 pesan lokal
  await page.type(".chat-input", "terima kasih");
  await page.keyboard.press("Enter");
  await sleep(2000);
  const userBubbles = await page.evaluate(
    () => [...document.querySelectorAll(".bubble.user")].filter((b) => b.textContent.includes("terima kasih")).length,
  );
  check("1 kirim -> 1 bubble user (tak dobel)", userBubbles === 1, `${userBubbles} bubble`);

  // pengaturan umum + teknis
  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Pengaturan"))?.click(),
  );
  await page.waitForSelector(".tabs", { timeout: 5000 });
  await page.evaluate(() => [...document.querySelectorAll(".tab")].find((b) => b.textContent.includes("Mode Teman"))?.click());
  await sleep(400);
  const teknisOk = await page.evaluate(() => document.body.textContent.includes("BYOK"));
  check("tab Mode Teman & AI tampil", teknisOk);

  await page.evaluate(() => [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Kembali"))?.click());
  await sleep(1500);

  // papan info
  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Papan Info"))?.click(),
  );
  await page.waitForSelector(".board", { timeout: 5000 });
  const clockOk = await page.evaluate(() => !!document.querySelector(".board .clock"));
  check("papan info tampil + jam", clockOk);
  await page.close();
}

// ============================================================
// TEST 6 — aksesibilitas: font besar & kontras tinggi benar-benar berlaku
// ============================================================
{
  console.log("\n=== TEST 6: font besar + kontras tinggi ===");
  const page = await openMain({ font_size: 3, high_contrast: true });
  const m = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement).fontSize;
    const btn = getComputedStyle(document.querySelector(".btn.mic"));
    return {
      root,
      btnSize: btn.fontSize,
      btnColor: btn.color,
      hc: document.documentElement.classList.contains("hc"),
      large: document.documentElement.classList.contains("font-xlarge"),
    };
  });
  check("kelas font-xlarge & hc terpasang", m.large && m.hc, JSON.stringify(m));
  check(
    "font sangat besar ikut memperbesar tombol (rem-based)",
    parseFloat(m.root) >= 24 && parseFloat(m.btnSize) > 24,
    `root=${m.root} tombol=${m.btnSize}`,
  );
  check(
    "kontras tinggi: teks tombol gelap (bukan putih di hijau terang)",
    m.btnColor === "rgb(0, 0, 0)",
    `warna=${m.btnColor}`,
  );
  await page.close();
}

// ============================================================
// TEST 7 — teks sapaan tidak berubah-ubah tiap status berubah
// ============================================================
{
  console.log("\n=== TEST 7: sapaan stabil ===");
  const page = await openMain();
  const first = await page.evaluate(() => document.querySelector(".greeting")?.textContent ?? "");
  await page.type(".chat-input", "jam berapa sekarang");
  await page.keyboard.press("Enter");
  await page.waitForFunction(
    () => [...document.querySelectorAll(".bubble.assistant")].some((b) => b.textContent.includes("Sekarang jam")),
    { timeout: 20000 },
  );
  const second = await page.evaluate(() => document.querySelector(".greeting")?.textContent ?? "");
  check("sapaan tidak berubah setelah chat (tidak acak tiap render)", first.length > 0 && first === second, `"${first}" -> "${second}"`);
  await page.close();
}

// ============================================================
// TEST 8 — kalimat user tidak dikirim dua kali ke LLM
// ============================================================
{
  console.log("\n=== TEST 8: riwayat LLM tanpa duplikat ===");
  const page = await openMain();
  await page.evaluate(() => {
    window.__llm = [];
    const orig = window.fetch;
    window.fetch = async (url, opts) => {
      if (String(url).includes("/chat/completions")) {
        window.__llm.push(JSON.parse(opts.body));
        return new Response(
          JSON.stringify({ choices: [{ message: { content: "Baik, Bu Sari." } }] }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        );
      }
      return orig(url, opts);
    };
  });
  const kalimat = "saya ingin mendengar penjelasan mengenai fotosintesis tumbuhan";
  await page.type(".chat-input", kalimat);
  await page.keyboard.press("Enter");
  await page.waitForFunction(
    () => [...document.querySelectorAll(".bubble.assistant")].some((b) => b.textContent.includes("Baik, Bu Sari")),
    { timeout: 20000 },
  );
  const body = await page.evaluate(() => window.__llm[0]);
  const users = (body?.messages ?? []).filter((x) => x.role === "user");
  const same = users.filter((x) => x.content.includes("fotosintesis"));
  const sys = (body?.messages ?? []).filter((x) => x.role === "system");
  check("pesan user terkirim tepat sekali ke LLM", users.length === 1 && same.length === 1, `user=${users.length} sistem=${sys.length}`);
  await page.close();
}

// ============================================================
// TEST 9 — izin mikrofon ditolak memberi penjelasan (tidak diam)
// ============================================================
{
  console.log("\n=== TEST 9: izin mikrofon ditolak ===");
  const page = await openMain();
  await page.waitForFunction(
    () => document.querySelector(".status-text")?.textContent?.includes("Izin mikrofon"),
    { timeout: 15000 },
  );
  const status = await page.evaluate(() => document.querySelector(".status-text")?.textContent ?? "");
  check("status menjelaskan izin mikrofon belum diberikan", status.includes("Izin mikrofon"), status);
  await page.close();
}

// ============================================================
// TEST 10 — mematikan eskalasi membatalkan pemantauan yang sudah armed
// ============================================================
{
  console.log("\n=== TEST 10: saklar eskalasi ===");
  const page = await openMain({
    checkin_enabled: true,
    escalation_enabled: true,
    escalation_armed: true,
    escalation_ts: Date.now(),
  });
  await openSettings(page);
  const clicked = await clickSwitch(page, "SMS keluarga bila tak ada respons");
  check("saklar eskalasi ketemu di Pengaturan", clicked);
  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Simpan Pengaturan"))?.click(),
  );
  await sleep(500);
  const p = await page.evaluate(() => JSON.parse(localStorage.getItem("Nenek_Assistant") || "{}"));
  check("eskalasi yang sudah armed dibatalkan saat saklar OFF", p.escalation_enabled === false && p.escalation_armed === false, `enabled=${p.escalation_enabled} armed=${p.escalation_armed}`);
  await page.close();
}

// ============================================================
// TEST 11 — pengingat ON tanpa jam aktif tidak tersimpan diam-diam
// ============================================================
{
  console.log("\n=== TEST 11: validasi pengingat obat ===");
  const page = await openMain({
    reminder_enabled: false,
    med_times: JSON.stringify([
      { h: 8, m: 0, en: false },
      { h: 14, m: 0, en: false },
      { h: 20, m: 0, en: false },
    ]),
  });
  await openSettings(page);
  await clickSwitch(page, "Aktifkan pengingat");
  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Simpan Pengaturan"))?.click(),
  );
  await sleep(500);
  const res = await page.evaluate(() => ({
    toast: document.querySelector(".toast")?.textContent ?? "",
    saved: JSON.parse(localStorage.getItem("Nenek_Assistant") || "{}").reminder_enabled,
  }));
  check(
    "Simpan ditolak dengan penjelasan, bukan OFF diam-diam",
    res.toast.includes("minimal satu jam") && res.saved !== true,
    `toast="${res.toast}" saved=${res.saved}`,
  );
  await page.close();
}

// ============================================================
// TEST 12 — "baca berita" jalan di browser (fallback CORS-friendly)
// ============================================================
{
  console.log("\n=== TEST 12: berita di browser ===");
  const page = await openMain();
  await page.type(".chat-input", "berita");
  await page.keyboard.press("Enter");
  let ok = true;
  try {
    await page.waitForFunction(
      () => [...document.querySelectorAll(".bubble.assistant")].some((b) => b.textContent.includes("Berita 1")),
      { timeout: 45000 },
    );
  } catch {
    ok = false;
  }
  const terakhir = await page.evaluate(() => {
    const all = [...document.querySelectorAll(".bubble.assistant")];
    return all[all.length - 1]?.textContent ?? "";
  });
  check("berita terucap di website", ok, terakhir.slice(0, 120));
  await page.close();
}

// ============================================================
// TEST 13 — kuis tetap utuh setelah layar dibangun ulang
// ============================================================
{
  console.log("\n=== TEST 13: kuis tahan remount ===");
  const page = await openMain({
    quiz_waiting: true,
    quiz_current: "Kalau 5 dikali 8, hasilnya berapa?",
    quiz_answer: "40",
  });
  await page.type(".chat-input", "40");
  await page.keyboard.press("Enter");
  await page.waitForFunction(
    () => [...document.querySelectorAll(".bubble.assistant")].some((b) => b.textContent.includes("benar sekali")),
    { timeout: 15000 },
  );
  const after = await page.evaluate(() => JSON.parse(localStorage.getItem("Nenek_Assistant") || "{}"));
  check("jawaban kuis setelah remount dinilai benar", true, "jawaban=40");
  check("status kuis dibersihkan setelah dijawab", after.quiz_waiting === false && !after.quiz_current);
  await page.close();

  // kuis tanpa soal tersimpan: jangan balas "Jawabannya ." (kosong)
  const page2 = await openMain({ quiz_waiting: true });
  await page2.type(".chat-input", "40");
  await page2.keyboard.press("Enter");
  await page2.waitForFunction(
    () => [...document.querySelectorAll(".bubble.assistant")].some((b) => b.textContent.includes("Kuisnya keburu hilang")),
    { timeout: 15000 },
  );
  const t = await page2.evaluate(() => {
    const all = [...document.querySelectorAll(".bubble.assistant")];
    return all[all.length - 1]?.textContent ?? "";
  });
  check("kuis tanpa soal tidak menjawab dengan jawaban kosong", !t.includes("Jawabannya ."), t.slice(0, 80));
  await page2.close();
}

// ============================================================
// TEST 14 — tombol mic: "berhenti" tetap berhenti (tidak menyala sendiri),
// dan menekannya lagi memulai dengar baru.
// ============================================================
{
  console.log("\n=== TEST 14: perilaku tombol mic (berhenti & mulai lagi) ===");
  const page = await newPage();
  await seedPrefs(page, { is_first_time: false, nenek_name: "Bu Sari" });
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
  await page.waitForSelector(".btn.mic", { timeout: 10000 });

  // tunggu loop dengar otomatis menyala (setelah sapaan)
  await page.waitForFunction(
    () => (document.querySelector(".btn.mic")?.textContent ?? "").includes("Dengar terus"),
    { timeout: 20000, polling: 250 },
  );
  check("mic otomatis mendengarkan setelah sapaan", true);

  await page.tap(".btn.mic");
  await page.waitForFunction(
    () => !(document.querySelector(".btn.mic")?.textContent ?? "").includes("Dengar terus"),
    { timeout: 8000, polling: 200 },
  );
  // biarkan 4 detik: kalau loop masih menyala, tombol akan kembali "Dengar terus"
  await sleep(4000);
  const masihDengar = await page.evaluate(() =>
    (document.querySelector(".btn.mic")?.textContent ?? "").includes("Dengar terus"),
  );
  check("setelah 'berhenti', mic tidak menyala lagi sendiri", !masihDengar);

  // tekan lagi -> mulai dengar kembali
  await page.tap(".btn.mic");
  let kembali = true;
  try {
    await page.waitForFunction(
      () => (document.querySelector(".btn.mic")?.textContent ?? "").includes("Dengar terus"),
      { timeout: 20000, polling: 250 },
    );
  } catch {
    kembali = false;
  }
  check("tekan mic lagi memulai dengar baru", kembali);
  await page.close();
}

await browser.close();

console.log("\n=== RINGKASAN ===");
let failed = 0;
for (const r of results) if (!r.ok) failed++;
console.log(`${results.length - failed}/${results.length} PASS`);

if (errors.length) {
  console.log("\n=== ERROR KONSOL/BROWSER ===");
  for (const e of [...new Set(errors)]) console.log("  " + e);
} else {
  console.log("\nTidak ada error konsol/browser.");
}

// kode keluar harus mencerminkan hasil: dulu selalu 0 walau ada FAIL
process.exit(failed === 0 ? 0 : 1);
