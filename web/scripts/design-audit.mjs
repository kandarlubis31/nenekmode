/**
 * Audit desain MOBILE FIRST — ukuran nyata, bukan sekadar baca CSS.
 *
 * Untuk tiap viewport (HP kecil 360x640, iPhone 390x844, Pixel 412x915):
 *   1. overflow horizontal (halaman & elemen yang melebar keluar layar)
 *   2. target sentuh < 44px (standar minimum Apple/Android)
 *   3. tombol penting (mic, darurat, kirim, pengaturan) terlihat tanpa scroll
 *   4. tinggi total scroll vs tinggi layar
 *   5. input font < 16px (di iOS Safari bikin zoom otomatis saat fokus)
 *   6. screenshot tiap layar buat lihat hasilnya sendiri
 *
 * Jalankan (preview :4173): node scripts/design-audit.mjs [url]
 * Screenshot tersimpan di design/.
 */

import puppeteer from "puppeteer-core";
import { mkdirSync, readFileSync } from "node:fs";

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const URL = process.argv[2] ?? "http://localhost:4173/";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mkdirSync("design", { recursive: true });

const VIEWS = [
  { w: 360, h: 640, name: "small-360x640" },
  { w: 390, h: 844, name: "iphone-390x844" },
  { w: 412, h: 915, name: "pixel-412x915" },
];

const results = [];
const check = (name, ok, detail = "") => {
  results.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`);
};

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ["--no-sandbox", "--autoplay-policy=no-user-gesture-required"],
});

const newPage = async (w, h) => {
  const page = await browser.newPage();
  await page.setViewport({ width: w, height: h, hasTouch: true, isMobile: true });
  // mic dimatikan: audit ini soal tata letak, bukan voice pipeline
  await page.evaluateOnNewDocument(() => {
    Object.defineProperty(navigator, "mediaDevices", {
      configurable: true,
      value: { getUserMedia: () => Promise.reject(new Error("mic off")) },
    });
  });
  return page;
};

const seed = (page, obj) =>
  page.evaluateOnNewDocument((o) => {
    localStorage.setItem("Nenek_Assistant", JSON.stringify(o));
  }, obj);

const openMain = async (w, h, extra = {}) => {
  const page = await newPage(w, h);
  await seed(page, { is_first_time: false, nenek_name: "Bu Sari", assistant_name: "Kandar", ...extra });
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
  await page.waitForSelector(".btn.mic", { timeout: 10000 });
  await sleep(2500); // lewati sapaan
  return page;
};

/** Ukuran viewport + posisi elemen penting dalam layar pertama. */
const measureLayout = (page) =>
  page.evaluate(() => {
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const doc = document.documentElement;

    // 1. overflow horizontal: elemen yang melebar keluar layar
    const overflowing = [];
    for (const el of document.querySelectorAll("body *")) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      if (r.right > vw + 1 || r.left < -1) {
        const id = el.tagName.toLowerCase() +
          (el.className && typeof el.className === "string" ? "." + el.className.trim().split(/\s+/).join(".") : "");
        overflowing.push(`${id} (${Math.round(r.left)}..${Math.round(r.right)})`);
      }
    }

    // 2. target sentuh kecil
    const small = [];
    for (const el of document.querySelectorAll("button, input, select, a[href]")) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      if (r.height < 44 || r.width < 44) {
        const label = (el.textContent ?? el.getAttribute("aria-label") ?? el.getAttribute("placeholder") ?? "")
          .trim()
          .slice(0, 26);
        const id = el.tagName.toLowerCase() +
          (el.className && typeof el.className === "string" ? "." + el.className.trim().split(/\s+/).join(".") : "");
        small.push(`${id}${label ? ` "${label}"` : ""} ${Math.round(r.width)}x${Math.round(r.height)}`);
      }
    }

    // 3. posisi tombol penting
    const findBtn = (t) =>
      [...document.querySelectorAll("button")].find((b) => b.textContent.includes(t));
    const pos = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { top: Math.round(r.top), bottom: Math.round(r.bottom), inView: r.top < vh && r.bottom > 0 };
    };

    // 5. input terlalu kecil buat iOS
    const tinyInputs = [];
    for (const el of document.querySelectorAll("input, select, textarea")) {
      const fs = parseFloat(getComputedStyle(el).fontSize);
      if (fs < 16 && fs > 0) tinyInputs.push(`${el.type ?? el.tagName} ${fs}px`);
    }

    // 6. bar bawah tetap: apakah tombol fitur (terakhir) tertutup bar saat
    //    halaman sudah di-scroll paling bawah?
    const bar = document.querySelector(".bottom-bar");
    const screen = document.querySelector(".screen.main");
    let barTop = null;
    let barH = 0;
    let lastBtnBottom = null;
    let hidden = "";
    if (bar && screen) {
      const br = bar.getBoundingClientRect();
      barTop = Math.round(br.top);
      barH = Math.round(br.height);
      screen.scrollTop = screen.scrollHeight;
      // hanya baris anak langsung (bar bawah juga punya .btn-row, tapi dia
      // fixed & justru jadi acuan)
      const rows = [...screen.querySelectorAll(":scope > .btn-row")];
      const lastRow = rows[rows.length - 1];
      if (lastRow) {
        const lr = lastRow.getBoundingClientRect();
        lastBtnBottom = Math.round(lr.bottom);
        if (lr.bottom > br.top + 1) {
          hidden = `tombol terakhir ${Math.round(lr.bottom)}px > bar ${Math.round(br.top)}px`;
        }
      }
      screen.scrollTop = 0;
    }

    // 7. ruang chat yang terlihat + porsi layar yg dimakan bar + tabrakan
    const chatEl = document.querySelector(".chat-box");
    const chatH = chatEl ? Math.round(chatEl.getBoundingClientRect().height) : 0;
    const barShare = bar ? bar.getBoundingClientRect().height / vh : 0;
    // elemen anak langsung .screen.main (bar dikecualikan, dia memang fixed
    // di atas area padding)
    const kids = screen
      ? [...screen.querySelectorAll(":scope > *")].filter((el) => !el.classList.contains("bottom-bar"))
      : [];
    const overlaps = [];
    for (let i = 0; i < kids.length; i++) {
      for (let j = i + 1; j < kids.length; j++) {
        const a = kids[i].getBoundingClientRect();
        const b = kids[j].getBoundingClientRect();
        const ox = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        const oy = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (ox > 2 && oy > 2) {
          overlaps.push(`${i}x${j} (${Math.round(ox)}x${Math.round(oy)})`);
        }
      }
    }
    if (screen) screen.scrollTop = 0;

    return {
      vw,
      vh,
      scrollW: doc.scrollWidth,
      scrollH: doc.scrollHeight,
      overflowing: overflowing.slice(0, 8),
      overflowCount: overflowing.length,
      small,
      mic: pos(document.querySelector(".btn.mic")),
      emergency: pos(findBtn("DARURAT")),
      send: pos(findBtn("Kirim")),
      settings: pos(findBtn("Pengaturan")),
      chatInput: pos(document.querySelector(".chat-input")),
      tinyInputs: [...new Set(tinyInputs)],
      barTop,
      barH,
      lastBtnBottom,
      hidden,
      chatH,
      barShare: Math.round(barShare * 100),
      overlaps,
    };
  });

// ============================================================
// A. AUDIT LAYAR UTAMA DI TIGA UKURAN
// ============================================================
console.log("\n=== A. LAYAR UTAMA ===");
for (const v of VIEWS) {
  console.log(`\n--- ${v.w}x${v.h} ---`);
  const page = await openMain(v.w, v.h);
  const m = await measureLayout(page);

  check(
    `tanpa overflow horizontal (${v.name})`,
    m.scrollW <= m.vw + 1,
    `scrollWidth=${m.scrollW} vs viewport=${m.vw}` +
      (m.overflowCount ? ` | ${m.overflowing.join(" | ")}` : ""),
  );
  check(
    `semua target sentuh >= 44px (${v.name})`,
    m.small.length === 0,
    m.small.length ? `${m.small.length} kecil: ${m.small.slice(0, 5).join(" | ")}` : "semua layak sentuh",
  );
  check(
    `mic + darurat + kirim terlihat tanpa scroll (${v.name})`,
    Boolean(m.mic?.inView && m.emergency?.inView && m.send?.inView),
    `mic=${m.mic ? (m.mic.inView ? "terlihat" : `di ${m.mic.top}px`) : "-"} ` +
      `darurat=${m.emergency ? (m.emergency.inView ? "terlihat" : `di ${m.emergency.top}px`) : "-"} ` +
      `kirim=${m.send ? (m.send.inView ? "terlihat" : `di ${m.send.top}px`) : "-"}`,
  );
  check(
    `tinggi scroll wajar (<= 3x tinggi layar, ${v.name})`,
    m.scrollH <= m.vh * 3,
    `scrollHeight=${m.scrollH} vs tinggi layar=${m.vh}`,
  );
  check(
    `input >= 16px biar iOS tidak zoom sendiri (${v.name})`,
    m.tinyInputs.length === 0,
    m.tinyInputs.length ? m.tinyInputs.join(", ") : "semua 16px+",
  );
  check(
    `konten tak tertutup bar bawah tetap saat scroll paling bawah (${v.name})`,
    !m.hidden,
    m.hidden || `tombol terakhir=${m.lastBtnBottom}px bar=${m.barTop}px (tinggi bar ${m.barH}px)`,
  );
  check(
    `ruang chat terbaca (>= 120px, ${v.name})`,
    m.chatH >= 120,
    `tinggi chat=${m.chatH}px`,
  );
  check(
    `bar bawah tidak menelan layar (> 45%, ${v.name})`,
    m.barShare <= 45,
    `bar=${m.barShare}% layar`,
  );
  check(
    `tidak ada elemen saling menimpa (${v.name})`,
    m.overlaps.length === 0,
    m.overlaps.length ? m.overlaps.join(" | ") : "rapi",
  );

  await page.screenshot({ path: `design/main-${v.name}.png`, fullPage: false });
  await page.screenshot({ path: `design/main-full-${v.name}.png`, fullPage: true });
  await page.close();
}

// ============================================================
// B. LAIN-LAIN (390x844)
// ============================================================
{
  const W = 390;
  const H = 844;
  console.log(`\n=== B. LAYAR LAIN (${W}x${H}) ===`);

  // wizard
  let page = await newPage(W, H);
  await seed(page, { nenek_name: "Bu Sari", assistant_name: "Kandar" });
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 30000 });
  await page.waitForSelector(".wizard", { timeout: 10000 });
  const wz = await page.evaluate(() => {
    const doc = document.documentElement;
    const btns = [...document.querySelectorAll(".wizard button")].map((b) => {
      const r = b.getBoundingClientRect();
      return `${b.textContent.trim().slice(0, 18)} ${Math.round(r.width)}x${Math.round(r.height)}`;
    });
    return { scrollW: doc.scrollWidth, vw: window.innerWidth, btns };
  });
  check("wizard tanpa overflow horizontal", wz.scrollW <= wz.vw + 1, `scrollWidth=${wz.scrollW}`);
  await page.screenshot({ path: "design/wizard-390x844.png" });
  await page.close();

  // pengaturan: umum & teknis
  page = await openMain(W, H);
  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Pengaturan"))?.click(),
  );
  await page.waitForSelector(".tabs", { timeout: 5000 });
  const st = await page.evaluate(() => {
    const doc = document.documentElement;
    const tabH = [...document.querySelectorAll(".tab")].map((t) => Math.round(t.getBoundingClientRect().height));
    const small = [];
    for (const el of document.querySelectorAll("button, input, select")) {
      const r = el.getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && (r.height < 44 || r.width < 44)) {
        small.push(`${el.className || el.tagName} ${Math.round(r.width)}x${Math.round(r.height)}`);
      }
    }
    return { scrollW: doc.scrollWidth, vw: window.innerWidth, tabH, small };
  });
  check("pengaturan umum tanpa overflow", st.scrollW <= st.vw + 1, `scrollWidth=${st.scrollW}`);
  check("tab cukup tinggi buat sentuh", st.tabH.every((h) => h >= 44), `tinggi tab=${st.tabH.join(",")}px`);
  check(
    "pengaturan: tidak ada target sentuh kecil",
    st.small.length === 0,
    st.small.length ? `${st.small.length}: ${st.small.slice(0, 4).join(" | ")}` : "ok",
  );
  await page.screenshot({ path: "design/settings-umum-390x844.png", fullPage: true });
  await page.evaluate(() => [...document.querySelectorAll(".tab")].find((t) => t.textContent.includes("Mode Teman"))?.click());
  await sleep(400);
  await page.screenshot({ path: "design/settings-teknis-390x844.png", fullPage: true });
  const st2 = await page.evaluate(() => {
    const doc = document.documentElement;
    const small = [];
    for (const el of document.querySelectorAll("button, input, select")) {
      const r = el.getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && (r.height < 44 || r.width < 44)) {
        small.push(
          `${el.tagName.toLowerCase()}${el.type ? `[${el.type}]` : ""} ` +
            `"${(el.getAttribute("aria-label") ?? el.textContent ?? "").trim().slice(0, 24)}" ` +
            `${Math.round(r.width)}x${Math.round(r.height)}`,
        );
      }
    }
    return { scrollW: doc.scrollWidth, vw: window.innerWidth, small };
  });
  check("pengaturan teknis tanpa overflow", st2.scrollW <= st2.vw + 1, `scrollWidth=${st2.scrollW}`);
  check(
    "pengaturan teknis: tidak ada target sentuh kecil",
    st2.small.length === 0,
    st2.small.length ? `${st2.small.length}: ${st2.small.slice(0, 5).join(" | ")}` : "ok",
  );
  await page.close();

  // papan info
  page = await openMain(W, H);
  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Papan Info"))?.click(),
  );
  await page.waitForSelector(".board", { timeout: 5000 });
  const bd = await page.evaluate(() => {
    const c = document.querySelector(".board .clock");
    const r = c ? c.getBoundingClientRect() : null;
    return {
      scrollW: document.documentElement.scrollWidth,
      vw: window.innerWidth,
      scrollH: document.documentElement.scrollHeight,
      vh: window.innerHeight,
      clockH: r ? Math.round(r.height) : 0, // jangan kirim DOMRect (JSON-nya kosong)
      clockFs: c ? parseFloat(getComputedStyle(c).fontSize) : 0,
    };
  });
  check(
    "papan info tanpa overflow & muat satu layar",
    bd.scrollW <= bd.vw + 1 && bd.scrollH <= bd.vh + 1,
    `scrollW=${bd.scrollW}/${bd.vw} scrollH=${bd.scrollH}/${bd.vh}`,
  );
  check(
    "jam papan cukup besar buat jarak pandang (>= 64px)",
    bd.clockH >= 64,
    `tinggi jam=${bd.clockH}px font=${bd.clockFs}px`,
  );
  await page.screenshot({ path: "design/board-390x844.png" });
  await page.close();
}

// ============================================================
// C. FONT BESAR + KONTRAS TINGGI (360x640 — paling sempit)
// ============================================================
{
  console.log("\n=== C. FONT X-LARGE + KONTRAS TINGGI (360x640) ===");
  const page = await openMain(360, 640, { font_size: 3, high_contrast: true });
  const m = await measureLayout(page);
  check(
    "tanpa overflow horizontal saat font x-large",
    m.scrollW <= m.vw + 1,
    `scrollWidth=${m.scrollW} vs viewport=${m.vw}` +
      (m.overflowCount ? ` | ${m.overflowing.join(" | ")}` : ""),
  );
  check(
    "mic + darurat + kirim tetap terlihat saat font x-large",
    Boolean(m.mic?.inView && m.emergency?.inView && m.send?.inView),
    `mic=${m.mic?.inView} darurat=${m.emergency?.inView} kirim=${m.send?.inView}`,
  );
  check(
    "konten tak tertutup bar saat font x-large (bar ikut membesar)",
    !m.hidden,
    m.hidden || `tombol terakhir=${m.lastBtnBottom}px bar=${m.barTop}px (tinggi bar ${m.barH}px)`,
  );
  check(
    "target sentuh tetap layak saat font x-large",
    m.small.length === 0,
    m.small.length ? `${m.small.length}: ${m.small.slice(0, 4).join(" | ")}` : "ok",
  );
  check(
    "ruang chat tetap ada saat font x-large (>= 120px)",
    m.chatH >= 120,
    `tinggi chat=${m.chatH}px`,
  );
  check(
    "tidak ada elemen saling menimpa saat font x-large",
    m.overlaps.length === 0,
    m.overlaps.length ? m.overlaps.join(" | ") : "rapi",
  );
  await page.screenshot({ path: "design/main-fontxlarge-hc-360x640.png", fullPage: true });
  await page.close();
}

// ============================================================
// D. KONTRAS TEKS vs WCAG AA (normal & mode kontras tinggi)
// ============================================================
const contrastAudit = (targets) => {
  const lum = (rgb) => {
    const [r, g, b] = rgb.map((v) => {
      v /= 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const parse = (s) => (String(s).match(/[0-9.]+/g) ?? []).slice(0, 3).map(Number);
  const bgOf = (el) => {
    let e = el;
    while (e) {
      const c = getComputedStyle(e).backgroundColor;
      const nums = String(c).match(/[0-9.]+/g);
      if (nums && nums.length >= 3) {
        const alpha = nums.length >= 4 ? Number(nums[3]) : 1;
        if (alpha > 0) return nums.slice(0, 3).map(Number);
      }
      e = e.parentElement;
    }
    return [255, 255, 255];
  };
  const ratio = (a, b) => {
    const l1 = lum(a);
    const l2 = lum(b);
    return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
  };
  const out = [];
  for (const [name, sel] of targets) {
    const el = document.querySelector(sel);
    if (!el) continue;
    const cs = getComputedStyle(el);
    const fg = parse(cs.color);
    const bg = bgOf(el);
    if (fg.length !== 3 || bg.length !== 3) continue;
    const size = parseFloat(cs.fontSize);
    const weight = parseInt(cs.fontWeight, 10) || 400;
    // teks "besar" = >=24px, atau tebal >=18.66px (aturan WCAG)
    const large = size >= 24 || (size >= 18.66 && weight >= 700);
    const r = ratio(fg, bg);
    const min = large ? 3 : 4.5;
    out.push({
      name,
      ratio: Math.round(r * 100) / 100,
      min,
      size,
      weight,
      ok: r >= min,
    });
  }
  return out;
};

const MAIN_TARGETS = [
  ["status", ".status-text"],
  ["ikon status", ".status-icon"],
  ["sapaan", ".greeting"],
  ["baris cek harian", ".checkin-status"],
  ["tombol mic", ".btn.mic"],
  ["tombol darurat", ".btn.emergency"],
  ["tombol utama", ".btn-row .btn:not(.gray):not(.accent)"],
  ["bubble asisten", ".bubble.assistant"],
  ["bubble user", ".bubble.user"],
  ["kolom ketik", ".chat-input"],
];

const SETTINGS_TARGETS = [
  ["tab aktif", ".tab.active"],
  ["label form", ".field label"],
  ["hint form", ".field .hint"],
  ["catatan", ".note"],
  ["label saklar", ".switch-row span"],
];

/** tiap target WAJIB ketemu — kalau tidak, selector berubah tanpa disadari. */
const runContrast = async (label, targets, prep, extra = {}) => {
  const page = await openMain(390, 844, extra);
  if (prep) await prep(page);
  const rows = await page.evaluate(contrastAudit, targets);
  const missing = targets.filter((t) => !rows.some((r) => r.name === t[0])).map((t) => t[0]);
  const bad = rows.filter((x) => !x.ok);
  console.log(
    `  kontras [${label}]: ` +
      rows.map((x) => `${x.name}=${x.ratio}:1(>=${x.min})`).join(" "),
  );
  const detail =
    (bad.length ? `gagal: ${bad.map((x) => `${x.name} ${x.ratio}:1 < ${x.min}`).join(" | ")}. ` : "") +
    (missing.length ? `tak ketemu: ${missing.join(", ")}` : "");
  check(`kontras teks WCAG AA (${label})`, missing.length === 0 && bad.length === 0, detail || `${rows.length} elemen lolos`);
  await page.close();
};

const withBubbles = async (page) => {
  await page.type(".chat-input", "jam berapa sekarang");
  await page.keyboard.press("Enter");
  await page.waitForFunction(
    () => [...document.querySelectorAll(".bubble.assistant")].some((b) => b.textContent.includes("Sekarang jam")),
    { timeout: 20000 },
  );
};

const goSettings = async (page) => {
  await page.evaluate(() =>
    [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Pengaturan"))?.click(),
  );
  await page.waitForSelector(".tabs", { timeout: 5000 });
};

for (const hc of [false, true]) {
  const tema = hc ? "kontras tinggi" : "normal";
  await runContrast(`layar utama, tema ${tema}`, MAIN_TARGETS, withBubbles, hc ? { high_contrast: true } : {});
  await runContrast(`pengaturan, tema ${tema}`, SETTINGS_TARGETS, goSettings, hc ? { high_contrast: true } : {});
}

// varian warna status yang jarang kelihatan (dipilih lewat jalur status) —
// diuji langsung dari file CSS biar semua kasus tetap terperiksa.
{
  // (URL global sudah di-shadow variabel URL di atas — pakai path saja)
  const css = readFileSync("src/index.css", "utf8");
  const hexOf = (name) => css.match(new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`))?.[1];
  const toRgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const lum = (rgb) => {
    const [r, g, b] = rgb.map((v) => {
      v /= 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const ratio = (a, b) => {
    const l1 = lum(a);
    const l2 = lum(b);
    return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
  };
  const vsWhite = (name) => {
    const h = hexOf(name);
    return h ? { h, r: Math.round(ratio(toRgb(h), [255, 255, 255]) * 100) / 100 } : null;
  };
  // teks putih di kartu status (28px tebal -> ambang 3:1)
  const whiteCards = ["primary", "card-listen", "card-speak", "card-panic"];
  const badCards = whiteCards
    .map((n) => ({ n, v: vsWhite(n) }))
    .filter((x) => !x.v || x.v.r < 3)
    .map((x) => `${x.n}=${x.v?.r ?? "?"}`);
  check(
    "kartu status (teks putih) kontras >= 3:1",
    badCards.length === 0,
    badCards.length ? badCards.join(", ") : whiteCards.map((n) => `${n}=${vsWhite(n).r}`).join(" "),
  );
  // kartu kuning memakai teks gelap, bukan putih (putih cuma 2.0:1)
  const thinkRule = /\.status-card\.thinking\s*\{[^}]*color:\s*(#[0-9a-fA-F]{3,6}|rgb[^;]+|var\([^)]+\))/.exec(css);
  const thinkColor = thinkRule?.[1] ?? "";
  const thinkIsDark = Boolean(thinkColor) && !["#fff", "#ffffff", "white"].includes(thinkColor.toLowerCase());
  check(
    "kartu status 'berpikir' memakai teks gelap (bukan putih di atas kuning)",
    thinkIsDark,
    `color=${thinkColor || "(tidak ditemukan)"}`,
  );
}

await browser.close();

console.log("\n=== RINGKASAN DESAIN ===");
let failed = 0;
for (const r of results) if (!r.ok) failed++;
console.log(`${results.length - failed}/${results.length} PASS`);
console.log("Screenshot: design/*.png");
process.exit(failed === 0 ? 0 : 1);
