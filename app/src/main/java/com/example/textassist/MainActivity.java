package com.example.textassist;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView statusText, answerText;
    private Button scanButton, askButton, screenShareButton;
    private TextToSpeech tts;
    private String extractedText = "";

    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private static final int STT_REQUEST_CODE = 5001;
    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1001;

    private GenerativeModelFutures gemini;

    private final GmsDocumentScannerOptions scanOptions = new GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build();

    ActivityResultLauncher<IntentSenderRequest> scanLauncher;
    ActivityResultLauncher<String> micPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        answerText = findViewById(R.id.answerText);
        scanButton = findViewById(R.id.scanButton);
        askButton = findViewById(R.id.askButton);
        screenShareButton = findViewById(R.id.shareBtn);

        initTTS();
        initGemini();
        setupLaunchers();

        scanButton.setOnClickListener(v -> startDocumentScan());
        askButton.setOnClickListener(v -> startSpeechToText());
        screenShareButton.setOnClickListener(v -> attemptStartScreenShare());
    }

    private void initGemini() {
        GenerativeModel gm = new GenerativeModel(
                "gemini-2.0-flash",
                "USE_YOUR_GEMINI_API_KEY"
        );
        gemini = GenerativeModelFutures.from(gm);
    }

    private void initTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(1.0f);
            }
        });
    }

    private void setupLaunchers() {
        scanLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        GmsDocumentScanningResult r =
                                GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
                        handleDocumentScan(r);
                    }
                }
        );

        micPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (!granted) speak("Microphone permission needed.");
                });
    }

    private void attemptStartScreenShare() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this, "Enable 'Display over other apps'", Toast.LENGTH_LONG).show();
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_SHORT).show();
            return;
        }

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);

        if (req == STT_REQUEST_CODE && res == RESULT_OK && data != null) {
            ArrayList<String> speech = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (speech != null && !speech.isEmpty()) {
                askGemini(speech.get(0));
            }
        }

        if (req == SCREEN_CAPTURE_REQUEST_CODE) {
            if (res == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, ScreenAssistService.class);
                serviceIntent.putExtra("RESULT_CODE", res);
                serviceIntent.putExtra("DATA", data);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                moveTaskToBack(true);
            } else {
                speak("Screen permission denied.");
            }
        }
    }

    private void startDocumentScan() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        GmsDocumentScanning.getClient(scanOptions)
                .getStartScanIntent(this)
                .addOnSuccessListener(intentSender -> {
                    IntentSenderRequest req = new IntentSenderRequest.Builder(intentSender).build();
                    scanLauncher.launch(req);
                })
                .addOnFailureListener(e -> speak("Scanner error."));
    }

    private void handleDocumentScan(GmsDocumentScanningResult result) {
        if (result == null || result.getPages() == null || result.getPages().isEmpty()) {
            speak("Scan failed.");
            return;
        }
        runOCR(result.getPages().get(0).getImageUri());
    }

    private void runOCR(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener(text -> {
                        extractedText = text.getText();
                        speak("Document ready. Ask your question.");
                        statusText.setText("Document loaded. Ready.");
                    })
                    .addOnFailureListener(e -> speak("OCR failed."));
        } catch (Exception e) {
            speak("Error reading image.");
        }
    }

    private void startSpeechToText() {
        if (extractedText.isEmpty()) {
            speak("Please scan a document first.");
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about the document...");
        try {
            startActivityForResult(intent, STT_REQUEST_CODE);
        } catch (Exception e) {
            speak("Speech recognition error.");
        }
    }

    private void askGemini(String question) {
        String prompt = "You are a helpful assistant.\nScanned text:\n" + extractedText + "\nQuestion: " + question + "\nAnswer briefly.";
        Content content = new Content.Builder().addText(prompt).build();
        ListenableFuture<GenerateContentResponse> future = gemini.generateContent(content);

        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
            @Override
            public void onSuccess(GenerateContentResponse response) {
                String txt = response.getText();
                if (txt != null) {
                    String clean = txt.replace("*", "");
                    uiHandler.post(() -> {
                        answerText.setText(clean);
                        speak(clean);
                    });
                }
            }
            @Override
            public void onFailure(Throwable t) {
                uiHandler.post(() -> speak("Gemini error."));
            }
        }, executor);
    }

    private void speak(String text) {
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_main");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
