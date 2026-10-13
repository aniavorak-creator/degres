package com.degres.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Shows the Degrés page in a WebView and gives it the phone's speech recognition
 * through a small JavaScript bridge named "Voice".
 */
public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 7;
    private static final int MAX_QUICK_ERRORS = 6;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private SpeechRecognizer recognizer;
    private boolean dark;
    private boolean wanted = false;        // the person asked for continuous listening
    private boolean pendingStart = false;  // waiting for the microphone permission answer
    private int quickErrors = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = night == Configuration.UI_MODE_NIGHT_YES;

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(dark ? "#0f1218" : "#eceef3"));

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setTextZoom(100);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new VoiceBridge(), "Voice");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    /** Methods below are called from the page; they run on a background thread. */
    private class VoiceBridge {
        @JavascriptInterface
        public boolean isDark() {
            return dark;
        }

        @JavascriptInterface
        public void start() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    startListening();
                }
            });
        }

        @JavascriptInterface
        public void stop() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    stopListening(true);
                }
            });
        }
    }

    private void emit(String kind, String text) {
        if (web == null) return;
        String js = "window.onNativeSpeech && window.onNativeSpeech("
                + JSONObject.quote(kind) + "," + JSONObject.quote(text) + ")";
        web.evaluateJavascript(js, null);
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit("state", "error:unavailable");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        wanted = true;
        quickErrors = 0;
        emit("state", "listening");
        listenOnce();
    }

    private void stopListening(boolean tellPage) {
        wanted = false;
        ui.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.cancel();
        }
        if (tellPage) emit("state", "idle");
    }

    private void listenOnce() {
        if (!wanted) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizer.startListening(intent);
    }

    private void listenAgain(long delayMs) {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                listenOnce();
            }
        }, delayMs);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { quickErrors = 0; }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) emit("partial", list.get(0));
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) {
                quickErrors = 0;
                emit("final", list.get(0));
            }
            if (wanted) listenAgain(250);
        }

        @Override
        public void onError(int error) {
            if (!wanted) return;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    // Silence or nothing understood: keep listening.
                    listenAgain(250);
                    return;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    stopListening(false);
                    emit("state", "error:permission");
                    return;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    stopListening(false);
                    emit("state", "error:network");
                    return;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    if (recognizer != null) recognizer.cancel();
                    break;
                default:
                    break;
            }
            quickErrors++;
            if (quickErrors > MAX_QUICK_ERRORS) {
                stopListening(false);
                emit("state", "error:" + error);
                return;
            }
            listenAgain(500);
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_AUDIO) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pendingStart) {
            pendingStart = false;
            startListening();
        } else {
            pendingStart = false;
            emit("state", "error:permission");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop listening when the app leaves the screen.
        stopListening(true);
    }

    @Override
    protected void onDestroy() {
        wanted = false;
        ui.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
