package com.mafia.game;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;

import java.util.Locale;

/**
 * Methods the game's JavaScript can call as window.AndroidBridge.xxx(...).
 * Only methods marked @JavascriptInterface are visible to the page, and the game uses all four:
 * isAndroidApp, speak, stopSpeaking, vibrate. Every input from the page is checked.
 * They are called on a background thread.
 */
public class WebAppInterface {

    private static final long MAX_VIBRATION_MS = 1000; // the game's longest buzz is 350 ms

    private final Context context; // the application, never the screen, so nothing leaks
    private final TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile boolean closed = false;
    private volatile String pendingText = null; // spoken as soon as the voice engine is ready

    WebAppInterface(Context context) {
        this.context = context.getApplicationContext();
        this.tts = new TextToSpeech(this.context, this::onTtsInit);
    }

    private void onTtsInit(int status) {
        if (status != TextToSpeech.SUCCESS || closed) {
            return;
        }
        // Prefer US English (Vincent's voice); fall back to UK, Indian English, then phone default.
        Locale[] choices = {Locale.US, Locale.UK, Locale.forLanguageTag("en-IN"), Locale.getDefault()};
        for (Locale locale : choices) {
            int result = tts.setLanguage(locale);
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsReady = true;
                break;
            }
        }
        if (!ttsReady) {
            return;
        }
        tts.setSpeechRate(0.92f);
        tts.setPitch(0.96f);
        String text = pendingText;
        pendingText = null;
        if (text != null) {
            speak(text);
        }
    }

    @JavascriptInterface
    public boolean isAndroidApp() {
        return true;
    }

    @JavascriptInterface
    public void speak(String text) {
        if (closed || text == null || text.isEmpty()) {
            return;
        }
        int max = TextToSpeech.getMaxSpeechInputLength();
        if (text.length() > max) {
            text = text.substring(0, max); // the voice engine refuses anything longer
        }
        if (!ttsReady) {
            pendingText = text; // engine still starting: remember the latest line
            return;
        }
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MAFIA_UTTERANCE");
        } catch (Exception ignored) {
            // never let a voice problem crash the game
        }
    }

    @JavascriptInterface
    public void stopSpeaking() {
        pendingText = null;
        if (closed) {
            return;
        }
        try {
            tts.stop();
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    @SuppressWarnings("deprecation")
    public void vibrate(long durationMs) {
        if (closed || durationMs <= 0) {
            return;
        }
        long ms = Math.min(durationMs, MAX_VIBRATION_MS);
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
                VibratorManager manager =
                        (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager != null ? manager.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // Android 8+
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Exception ignored) {
        }
    }

    /** Release the voice engine when the app closes. */
    void shutdown() {
        closed = true;
        pendingText = null;
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception ignored) {
        }
    }
}
