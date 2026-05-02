package com.termux.app.terminal.io;

import android.Manifest;
import android.content.Intent;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.EditText;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.android.PermissionUtils;

import java.util.ArrayList;
import java.util.Locale;

public final class TermuxAiSpeechInput {

    public static final int REQUEST_RECORD_AUDIO_PERMISSION = 3000;

    private TermuxAiSpeechInput() {}

    public static void start(TermuxActivity activity, EditText editText) {
        if (!PermissionUtils.checkPermission(activity, Manifest.permission.RECORD_AUDIO)) {
            PermissionUtils.requestPermission(activity, Manifest.permission.RECORD_AUDIO,
                REQUEST_RECORD_AUDIO_PERMISSION);
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity, R.string.msg_voice_input_not_available, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(activity, R.string.msg_voice_input_listening, Toast.LENGTH_SHORT).show();

        SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(android.os.Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int eventType, android.os.Bundle params) {}

            @Override
            public void onError(int error) {
                recognizer.destroy();
                Toast.makeText(activity, R.string.msg_voice_input_error, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onResults(android.os.Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    editText.setText(matches.get(0));
                    editText.setSelection(editText.getText().length());
                }
                recognizer.destroy();
            }

            @Override
            public void onPartialResults(android.os.Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    editText.setText(matches.get(0));
                    editText.setSelection(editText.getText().length());
                }
            }
        });

        recognizer.startListening(intent);
    }
}
