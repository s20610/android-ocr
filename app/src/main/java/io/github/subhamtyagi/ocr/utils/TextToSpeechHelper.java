package io.github.subhamtyagi.ocr.utils;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public class TextToSpeechHelper {
    private TextToSpeech textToSpeech;

    public TextToSpeechHelper(Context context) {
        textToSpeech = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Log.d("TextToSpeechHelper", "TextToSpeech initialized successfully");
                textToSpeech.setLanguage(new Locale("pl", "PL"));
            }
        });
    }

    public void readText(String text) {
        if (textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, null);
        }
    }

    public void shutdown() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
}