/**
 * Perekaman mic dengan VAD sederhana — port AudioUtil.java (16 kHz mono PCM16).
 * Pakai Web Audio (ScriptProcessor) biar RMS/VAD-nya identik dgn native.
 */

export const SAMPLE_RATE = 16000;
export const VAD_THRESHOLD = 2.0; // skala int16, sama dgn AudioUtil
export const VAD_SILENCE_MS = 1500;
export const VAD_SPEECH_MIN_MS = 400;
export const MAX_RECORDING_MS = 15000;
/**
 * Rerata RMS minimum selama "bicara" (skala int16). Noise senyap / gema TTS
 * lolos VAD_THRESHOLD (2.0) tapi di bawah ini — Whisper sering meng-hallucinate
 * "terima kasih" pada audio hampir senyap, bikin asisten "jawab sendiri".
 * bicara asli dekat HP: RMS ~300-3000; 20 tetap sangat murah hati utk suara pelan.
 */
export const MIN_SPEECH_RMS = 20.0;

export type StopFlag = () => boolean;

/**
 * Rekam dari mic sampai selesai bicara (VAD) / timeout / stop.
 * @returns PCM16 bytes, atau null jika tidak ada suara / di-stop.
 */
export async function recordWithVad(stopFlag: StopFlag): Promise<Uint8Array | null> {
  let stream: MediaStream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true },
    });
  } catch {
    return null;
  }

  const ctx = new AudioContext({ sampleRate: SAMPLE_RATE });
  const source = ctx.createMediaStreamSource(stream);
  // ScriptProcessor deprecated tapi tersedia di semua WebView/Chrome
  const processor = ctx.createScriptProcessor(4096, 1, 1);

  const chunks: Float32Array[] = [];
  let speechStartTime = 0;
  let silenceStartTime = 0;
  let speechDetected = false;
  let rmsSum = 0;
  let rmsCount = 0;
  const startTime = Date.now();
  let finished = false;

  return await new Promise<Uint8Array | null>((resolve) => {
    let watchdog = 0;
    const finish = (result: Uint8Array | null) => {
      if (finished) return;
      finished = true;
      if (watchdog) window.clearTimeout(watchdog);
      // gerbang anti-halusinasi: audio dengan energi rerata rendah
      // (noise senyap / gema TTS pelan) jangan dikirim ke Whisper —
      // di situ Whisper sering mengarang "terima kasih"/"halo"
      if (result && rmsCount > 0 && rmsSum / rmsCount < MIN_SPEECH_RMS) {
        result = null;
      }
      try {
        processor.onaudioprocess = null;
        source.disconnect();
        processor.disconnect();
        stream.getTracks().forEach((t) => t.stop());
        void ctx.close();
      } catch {
        /* abaikan */
      }
      resolve(result);
    };

    processor.onaudioprocess = (e) => {
      if (finished) return;
      if (stopFlag()) {
        finish(null);
        return;
      }
      if (Date.now() - startTime >= MAX_RECORDING_MS) {
        finish(speechDetected ? concatPcm(chunks) : null);
        return;
      }

      const input = e.inputBuffer.getChannelData(0);
      const copy = new Float32Array(input.length);
      copy.set(input);
      chunks.push(copy);

      // RMS skala int16 (sama dgn AudioUtil.calculateRMS)
      let sum = 0;
      for (let i = 0; i < input.length; i++) {
        const s = Math.max(-1, Math.min(1, input[i])) * 32768;
        sum += s * s;
      }
      const rms = Math.sqrt(sum / input.length);
      rmsSum += rms;
      rmsCount++;

      if (rms > VAD_THRESHOLD) {
        if (!speechDetected) {
          speechDetected = true;
          speechStartTime = Date.now();
        }
        silenceStartTime = Date.now();
      } else if (speechDetected) {
        const speechDuration = Date.now() - speechStartTime;
        const silenceDuration = Date.now() - silenceStartTime;
        if (speechDuration >= VAD_SPEECH_MIN_MS && silenceDuration >= VAD_SILENCE_MS) {
          finish(concatPcm(chunks));
        }
      }
    };

    // WebView bisa men-suspend AudioContext (autoplay policy) sampai ada
    // sentuhan. Tanpa resume, onaudioprocess tak pernah dipanggil dan promise
    // tak pernah selesai -> tombol mic macet selamanya.
    void ctx.resume?.().catch(() => undefined);

    // jaring pengaman: pada sebagian WebView callback audio berhenti saat app
    // di-background. Jangan biarkan rekaman menggantung tanpa akhir.
    watchdog = window.setTimeout(() => {
      finish(speechDetected ? concatPcm(chunks) : null);
    }, MAX_RECORDING_MS + 3000);

    source.connect(processor);
    processor.connect(ctx.destination);
  });
}

function concatPcm(chunks: Float32Array[]): Uint8Array | null {
  let total = 0;
  for (const c of chunks) total += c.length;
  if (total === 0) return null;
  const out = new Uint8Array(total * 2);
  let offset = 0;
  for (const c of chunks) {
    for (let i = 0; i < c.length; i++) {
      const s = Math.max(-1, Math.min(1, c[i]));
      const v = s < 0 ? s * 0x8000 : s * 0x7fff;
      const n = Math.round(v);
      out[offset++] = n & 0xff;
      out[offset++] = (n >> 8) & 0xff;
    }
  }
  return out;
}

/** PCM16 mono -> Blob WAV (RIFF) — port writeWavFile MainActivity. */
export function encodeWav(pcm: Uint8Array, sampleRate: number): Blob {
  const dataSize = pcm.length;
  const buffer = new ArrayBuffer(44 + dataSize);
  const view = new DataView(buffer);
  const writeStr = (off: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i));
  };

  writeStr(0, "RIFF");
  view.setUint32(4, 36 + dataSize, true);
  writeStr(8, "WAVE");
  writeStr(12, "fmt ");
  view.setUint32(16, 16, true); // pcm chunk size
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // byte rate
  view.setUint16(32, 2, true); // block align
  view.setUint16(34, 16, true); // bits per sample
  writeStr(36, "data");
  view.setUint32(40, dataSize, true);
  new Uint8Array(buffer, 44).set(pcm);

  return new Blob([buffer], { type: "audio/wav" });
}
