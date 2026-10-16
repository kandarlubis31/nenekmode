/**
 * Bank sapaan variasi biar asisten gak gitu-gitu aja.
 * Port langsung GreetingBank.java.
 */

function pick(arr: string[]): string {
  return arr[Math.floor(Math.random() * arr.length)];
}

function morning(n: string, a: string): string {
  return pick([
    `Selamat pagi ${n}! Semoga hari ini sehat dan lancar ya. Udah sarapan belum?`,
    `Pagi ${n}! ${a} udah kangen ngobrol sama kamu nih. Gimana tidurnya, nyenyak?`,
    `Selamat pagi ${n}! Langit cerah lagi nih, semoga hatinya juga cerah ya. Mau sarapan apa hari ini?`,
    `Pagi ${n}! Yuk mulai hari dengan senyum, biar awet muda.`,
  ]);
}

function noon(n: string, a: string): string {
  return pick([
    `Siang ${n}! Udah makan siang belum? Jangan nunda makan ya.`,
    `Siang ${n}! ${a} mau nemenin ngobrol sebentar. Gimana hari ini, lancar-lancar aja kan?`,
    `Selamat siang ${n}! Panas-panas gini enaknya minum teh hangat sambil cerita-cerita.`,
    `Siang ${n}! Semangat ya, udah lewat setengah hari nih. Nanti sore kita ngobrol lagi ya.`,
  ]);
}

function afternoon(n: string, a: string): string {
  return pick([
    `Sore ${n}! Udah sore nih, mampir duduk dulu sambil cerita-cerita yuk.`,
    `Sore ${n}! ${a} mau dengerin cerita kamu hari ini. Ada yang seru nggak?`,
    `Selamat sore ${n}! Habis jalan-jalan sore? Udah minum air putih kan?`,
    `Sore ${n}! Waktu yang pas nih ngobrol sambil nunggu maghrib. Mau kuis ringan atau cerita dulu aja?`,
  ]);
}

function evening(n: string, a: string): string {
  return pick([
    `Malam ${n}! Sebelum tidur, ada yang mau diceritain nggak? Kalau ngantuk, istirahat aja ya.`,
    `Malam ${n}! ${a} mau ninabobin kamu. Udah mandi dan minum obat belum?`,
    `Selamat malam ${n}! Jangan begadang ya, besok masih ada hari indah lainnya. Mau ${a} doain sebelum tidur?`,
    `Malam ${n}! Semoga mimpi indah ya. Kalau gak bisa tidur, panggil aja ${a}, kita ngobrol pelan-pelan.`,
  ]);
}

export function greeting(timeOfDay: string, elderName: string, assistantName: string): string {
  switch (timeOfDay) {
    case "pagi":
      return morning(elderName, assistantName);
    case "siang":
      return noon(elderName, assistantName);
    case "sore":
      return afternoon(elderName, assistantName);
    default:
      return evening(elderName, assistantName);
  }
}
