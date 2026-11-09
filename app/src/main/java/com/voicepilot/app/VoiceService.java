package com.voicepilot.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Always-on listener: hears a command, then acts on the screen through accessibility. */
public class VoiceService extends AccessibilityService implements RecognitionListener {
    static VoiceService instance;
    static String status = "Service is off";

    final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady, speaking, asleep, screenOn = true;
    private long lastActivity;
    private String lastOriginal = "";
    private Screen screen;
    private CommandRunner runner;

    private final Runnable listen = this::startListening;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (screenOn && !speaking && SystemClock.elapsedRealtime() - lastActivity > 20000) {
                destroyRecognizer();
                startListening();
            }
            handler.postDelayed(this, 10000);
        }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            if (screenOn) schedule(300);
            else { handler.removeCallbacks(listen); destroyRecognizer(); if (screen != null) screen.hideNumbers(); }
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        screen = new Screen(this);
        runner = new CommandRunner(this, screen);
        screenOn = ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
        tts = new TextToSpeech(this, st -> ttsReady = st == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { doneSpeaking(); }
            @Override public void onError(String id) { doneSpeaking(); }
        });
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
        handler.postDelayed(watchdog, 10000);
        schedule(500);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (instance != this) return;
        instance = null;
        status = "Service is off";
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        destroyRecognizer();
        if (screen != null) screen.hideNumbers();
        if (tts != null) tts.shutdown();
    }

    // ---------- listening loop ----------

    private void schedule(long ms) {
        handler.removeCallbacks(listen);
        handler.postDelayed(listen, ms);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void startListening() {
        if (!screenOn || speaking || instance != this) return;
        lastActivity = SystemClock.elapsedRealtime();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission is missing. Open VoicePilot and allow it.";
            schedule(5000);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status = "No speech recognition on this phone. Install or enable the Google app.";
            schedule(15000);
            return;
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(this);
            }
            String lang = getSharedPreferences("cfg", 0).getString("lang", "en-US");
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName())
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 4);
            recognizer.startListening(i);
            status = asleep ? "Sleeping. Say \"wake up\"." : "Listening";
        } catch (Exception e) {
            status = "Could not start listening: " + e.getMessage();
            destroyRecognizer();
            schedule(3000);
        }
    }

    @Override public void onReadyForSpeech(Bundle b) { lastActivity = SystemClock.elapsedRealtime(); }
    @Override public void onBeginningOfSpeech() { lastActivity = SystemClock.elapsedRealtime(); }
    @Override public void onRmsChanged(float v) { lastActivity = SystemClock.elapsedRealtime(); }
    @Override public void onBufferReceived(byte[] b) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle b) { }
    @Override public void onEvent(int t, Bundle b) { }

    @Override
    public void onError(int error) {
        lastActivity = SystemClock.elapsedRealtime();
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            schedule(150);
        } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            status = "Microphone permission is missing. Open VoicePilot and allow it.";
            schedule(5000);
        } else {
            if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                status = "Speech recognition needs internet, or an offline language pack in the Google app.";
            }
            destroyRecognizer();
            schedule(1500);
        }
    }

    @Override
    public void onResults(Bundle results) {
        lastActivity = SystemClock.elapsedRealtime();
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) {
            try {
                handle(list);
            } catch (Exception e) {
                note("Error: " + e.getMessage());
            }
        }
        schedule(250);
    }

    private void handle(List<String> alts) {
        lastOriginal = alts.get(0).trim();
        String t = lastOriginal.toLowerCase().replaceAll("[.!?,]+$", "").trim();
        t = t.replaceFirst("^(please|okay|ok) ", "");
        if (t.isEmpty()) return;

        if (asleep) {
            if (t.matches("wake up|start listening|resume listening|listen")) { asleep = false; say("Listening."); }
            return;
        }
        if (t.matches("stop listening|go to sleep|sleep|pause listening")) {
            asleep = true;
            say("Sleeping. Say wake up to continue.");
            return;
        }
        String prefix = getSharedPreferences("cfg", 0).getString("prefix", "").toLowerCase().trim();
        if (!prefix.isEmpty()) {
            if (!t.startsWith(prefix + " ")) return;
            t = t.substring(prefix.length()).trim();
        }
        note(t);
        runner.run(t, alts);
    }

    /** Returns the given lower-cased fragment with the capitalisation the recogniser produced. */
    String original(String fragment) {
        int at = lastOriginal.toLowerCase().lastIndexOf(fragment);
        return at < 0 ? fragment : lastOriginal.substring(at, at + fragment.length());
    }

    // ---------- feedback ----------

    void note(String text) {
        try { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); } catch (Exception ignored) { }
    }

    void say(String text) {
        note(text);
        if (!ttsReady || tts == null) return;
        speaking = true;
        handler.removeCallbacks(listen);
        if (recognizer != null) { try { recognizer.cancel(); } catch (Exception ignored) { } }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vp") != TextToSpeech.SUCCESS) doneSpeaking();
        // Safety net in case the engine never reports completion.
        handler.postDelayed(this::doneSpeakingIfStuck, 15000);
    }

    private void doneSpeakingIfStuck() {
        if (speaking && (tts == null || !tts.isSpeaking())) doneSpeaking();
    }

    private void doneSpeaking() {
        handler.post(() -> {
            speaking = false;
            schedule(250);
        });
    }
}
