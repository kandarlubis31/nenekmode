package com.example.nenekmode;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/**
 * Util audio bersama: perekaman dengan VAD sederhana + konversi ke PCM bytes.
 * Dipakai MainActivity dan CompanionService supaya tidak duplikasi.
 */
public final class AudioUtil {

    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int BUFFER_SIZE =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

    public static final float VAD_THRESHOLD = 2.0f;
    public static final long VAD_SILENCE_MS = 1500L;
    public static final long VAD_SPEECH_MIN_MS = 400L;
    public static final long MAX_RECORDING_MS = 15000L;

    public interface StopFlag { boolean isStopped(); }

    private AudioUtil() {}

    /**
     * Rekam audio dari mic sampai user selesai bicara (VAD) atau timeout.
     * @return PCM 16-bit mono data, atau null jika tidak ada suara / di-stop.
     */
    public static byte[] recordWithVad(StopFlag stopFlag) {
        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) return null;

            recorder.startRecording();
            ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];

            long speechStartTime = 0;
            long silenceStartTime = 0;
            boolean speechDetected = false;
            long startTime = System.currentTimeMillis();

            while (!stopFlag.isStopped()
                    && (System.currentTimeMillis() - startTime) < MAX_RECORDING_MS) {
                int read = recorder.read(chunk, 0, chunk.length);
                if (read <= 0) continue;
                audioBuffer.write(chunk, 0, read);

                float rms = calculateRMS(chunk, read);
                if (rms > VAD_THRESHOLD) {
                    if (!speechDetected) {
                        speechDetected = true;
                        speechStartTime = System.currentTimeMillis();
                    }
                    silenceStartTime = System.currentTimeMillis();
                } else if (speechDetected) {
                    long speechDuration = System.currentTimeMillis() - speechStartTime;
                    long silenceDuration = System.currentTimeMillis() - silenceStartTime;
                    if (speechDuration >= VAD_SPEECH_MIN_MS && silenceDuration >= VAD_SILENCE_MS) {
                        break;
                    }
                }
            }

            if (!speechDetected || stopFlag.isStopped()) return null;
            byte[] data = audioBuffer.toByteArray();
            return data.length == 0 ? null : data;
        } catch (Exception e) {
            return null;
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception ignored) {}
                try {
                    recorder.release();
                } catch (Exception ignored) {}
            }
        }
    }

    public static float calculateRMS(byte[] audioData, int length) {
        if (length == 0) return 0;
        long sum = 0;
        int samples = length / 2;
        for (int i = 0; i < length - 1; i += 2) {
            short sample = (short) ((audioData[i] & 0xFF) | (audioData[i + 1] << 8));
            sum += (long) sample * sample;
        }
        return (float) Math.sqrt((double) sum / samples);
    }
}
