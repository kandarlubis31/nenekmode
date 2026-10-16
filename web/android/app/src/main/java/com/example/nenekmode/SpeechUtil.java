package com.example.nenekmode;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * TTS sekali-pakai untuk receiver (reminder, proactive, check-in):
 * bicara lalu shutdown otomatis setelah selesai.
 */
public final class SpeechUtil {

    private SpeechUtil() {}

    public static void speak(Context context, String text) {
        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(context, status -> {
            if (status != TextToSpeech.SUCCESS || holder[0] == null) return;
            holder[0].setLanguage(new Locale("id", "ID"));
            holder[0].setSpeechRate(0.75f);
            holder[0].setPitch(0.85f);
            holder[0].speak(text, TextToSpeech.QUEUE_FLUSH, null, "SPEECH_UTIL");
            // shutdown setelah selesai (estimasi durasi berdasar panjang teks)
            long durationMs = Math.max(3000, text.length() * 90L);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        if (holder[0] != null) holder[0].shutdown();
                    }, durationMs);
        });
    }
}
