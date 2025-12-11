//////////    package com.example.textassist;
//////////
//////////    import android.Manifest;
//////////    import android.content.Intent;
//////////    import android.content.pm.PackageManager;
//////////    import android.graphics.Bitmap;
//////////    import android.graphics.BitmapFactory;
//////////    import android.net.Uri;
//////////    import android.os.Build;
//////////    import android.os.Bundle;
//////////    import android.os.Handler;
//////////    import android.os.Looper;
//////////    import android.provider.Settings;
//////////    import android.speech.RecognizerIntent;
//////////    import android.speech.tts.TextToSpeech;
//////////    import android.util.Log;
//////////    import android.widget.Button;
//////////    import android.widget.TextView;
//////////    import android.widget.Toast;
//////////
//////////    import androidx.activity.result.ActivityResultLauncher;
//////////    import androidx.activity.result.IntentSenderRequest;
//////////    import androidx.activity.result.contract.ActivityResultContracts;
//////////    import androidx.annotation.Nullable;
//////////    import androidx.appcompat.app.AppCompatActivity;
//////////
//////////    import com.google.ai.client.generativeai.GenerativeModel;
//////////    import com.google.ai.client.generativeai.java.GenerativeModelFutures;
//////////    import com.google.ai.client.generativeai.type.Content;
//////////    import com.google.ai.client.generativeai.type.GenerateContentResponse;
//////////    import com.google.common.util.concurrent.FutureCallback;
//////////    import com.google.common.util.concurrent.Futures;
//////////    import com.google.common.util.concurrent.ListenableFuture;
//////////    import com.google.mlkit.vision.common.InputImage;
//////////    import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
//////////    import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
//////////    import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
//////////    import com.google.mlkit.vision.text.TextRecognition;
//////////    import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
//////////    import com.google.mlkit.vision.text.Text;
//////////
//////////    import java.io.InputStream;
//////////    import java.util.ArrayList;
//////////    import java.util.Locale;
//////////    import java.util.concurrent.Executor;
//////////    import java.util.concurrent.Executors;
//////////    import android.media.projection.MediaProjectionManager;
//////////
//////////
//////////    public class MainActivity extends AppCompatActivity {
//////////
//////////        private TextView statusText, answerText;
//////////        private Button scanButton, askButton;
//////////        private TextToSpeech tts;
//////////
//////////        private String extractedText = "";
//////////
//////////        private final Executor executor = Executors.newSingleThreadExecutor();
//////////        private final Handler uiHandler = new Handler(Looper.getMainLooper());
//////////
//////////        private static final int STT_REQUEST_CODE = 5001;
//////////
//////////        // Gemini
//////////        private GenerativeModelFutures gemini;
//////////
//////////        private static final int SCREEN_CAPTURE_REQUEST = 9001;
//////////
//////////        // Document scanner options
//////////        private final GmsDocumentScannerOptions scanOptions = new GmsDocumentScannerOptions.Builder()
//////////                .setGalleryImportAllowed(true)
//////////                .setPageLimit(1)
//////////                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
//////////                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
//////////                .build();
//////////
//////////        ActivityResultLauncher<IntentSenderRequest> scanLauncher;
//////////        ActivityResultLauncher<String> micPermissionLauncher;
//////////
//////////        @Override
//////////        protected void onCreate(Bundle savedInstanceState) {
//////////            super.onCreate(savedInstanceState);
//////////            setContentView(R.layout.activity_main);
//////////
//////////            if (!Settings.canDrawOverlays(this)) {
//////////                Intent intent = new Intent(
//////////                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
//////////                        Uri.parse("package:" + getPackageName())
//////////                );
//////////                startActivityForResult(intent, 6001);
//////////                return;
//////////            }
//////////
//////////
//////////            statusText = findViewById(R.id.statusText);
//////////            answerText = findViewById(R.id.answerText);
//////////            scanButton = findViewById(R.id.scanButton);
//////////            askButton = findViewById(R.id.askButton);
//////////
//////////            initTTS();
//////////            initGemini();
//////////            setupLaunchers();
//////////
//////////            scanButton.setOnClickListener(v -> startDocumentScan());
//////////            askButton.setOnClickListener(v -> startSpeechToText());
//////////            Button shareBtn = findViewById(R.id.shareBtn);
//////////            shareBtn.setOnClickListener(v -> requestScreenShare());
//////////
////////////            // Start floating bubble
////////////            startService(new Intent(this, FloatingOverlayService.class));
////////////
////////////            MediaProjectionManager mgr =
////////////                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
////////////
////////////            startActivityForResult(mgr.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST);
//////////
//////////        }
//////////
//////////        private void initGemini() {
//////////            GenerativeModel gm = new GenerativeModel(
//////////                    "gemini-2.0-flash",
//////////                    "AIzaSyA7nky9qXlTAIxakTI3M9kcFEjo64zDN1A"
//////////            );
//////////            gemini = GenerativeModelFutures.from(gm);
//////////        }
//////////
//////////        private void requestScreenShare() {
//////////            MediaProjectionManager mgr =
//////////                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
//////////
//////////            Intent intent = mgr.createScreenCaptureIntent();
//////////            startActivityForResult(intent, SCREEN_CAPTURE_REQUEST);
//////////        }
//////////
//////////        private void openCaptureVideoOutputSettings() {
//////////            try {
//////////                Intent intent = new Intent("android.settings.MANAGE_CAPTURE_VIDEO_OUTPUT");
//////////                intent.setData(Uri.parse("package:" + getPackageName()));
//////////                startActivity(intent);
//////////            } catch (Exception e) {
//////////                Toast.makeText(this,
//////////                        "Enable 'Capture video output' in Settings > Apps > Special app access.",
//////////                        Toast.LENGTH_LONG).show();
//////////            }
//////////        }
//////////
//////////
//////////
//////////        private void initTTS() {
//////////            tts = new TextToSpeech(this, status -> {
//////////                if (status == TextToSpeech.SUCCESS) {
//////////                    tts.setLanguage(Locale.getDefault());
//////////                    tts.setSpeechRate(1.0f);
//////////                }
//////////            });
//////////        }
//////////
//////////        private void setupLaunchers() {
//////////
//////////            scanLauncher = registerForActivityResult(
//////////                    new ActivityResultContracts.StartIntentSenderForResult(),
//////////                    result -> {
//////////                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
//////////                            GmsDocumentScanningResult r =
//////////                                    GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
//////////
//////////                            handleDocumentScan(r);
//////////                        }
//////////                    }
//////////            );
//////////
//////////            micPermissionLauncher =
//////////                    registerForActivityResult(new ActivityResultContracts.RequestPermission(),
//////////                            granted -> {
//////////                                if (!granted)
//////////                                    speak("Microphone permission needed.");
//////////                            });
//////////        }
//////////
//////////        private void startDocumentScan() {
//////////            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
//////////                    != PackageManager.PERMISSION_GRANTED) {
//////////                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
//////////            }
//////////
//////////            GmsDocumentScanning.getClient(scanOptions)
//////////                    .getStartScanIntent(this)
//////////                    .addOnSuccessListener(intentSender -> {
//////////                        IntentSenderRequest req = new IntentSenderRequest.Builder(intentSender).build();
//////////                        scanLauncher.launch(req);
//////////                    })
//////////                    .addOnFailureListener(e -> speak("Unable to open document scanner."));
//////////        }
//////////
//////////        private void handleDocumentScan(GmsDocumentScanningResult result) {
//////////            if (result == null || result.getPages() == null || result.getPages().isEmpty()) {
//////////                speak("Scan failed.");
//////////                return;
//////////            }
//////////
//////////            Uri uri = result.getPages().get(0).getImageUri();
//////////            runOCR(uri);
//////////        }
//////////
//////////        private void runOCR(Uri uri) {
//////////            try {
//////////                InputStream is = getContentResolver().openInputStream(uri);
//////////                Bitmap bitmap = BitmapFactory.decodeStream(is);
//////////                is.close();
//////////
//////////                InputImage image = InputImage.fromBitmap(bitmap, 0);
//////////
//////////                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
//////////                        .process(image)
//////////                        .addOnSuccessListener(text -> {
//////////
//////////                            extractedText = text.getText();
//////////
//////////                            // SHOW EXTRACTED OCR TEXT
//////////    //                        ocrTextView.setText(extractedText);
//////////
//////////                            speak("Document ready. Ask your question.");
//////////                            statusText.setText("Document loaded. Ready for questions.");
//////////
//////////                        })
//////////                        .addOnFailureListener(e -> {
//////////                            speak("OCR failed.");
//////////                        });
//////////
//////////            } catch (Exception e) {
//////////                speak("Error reading scanned image.");
//////////            }
//////////        }
//////////
//////////        private void startSpeechToText() {
//////////
//////////            if (extractedText.isEmpty()) {
//////////                speak("Please scan a document first.");
//////////                return;
//////////            }
//////////
//////////            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
//////////            intent.putExtra(
//////////                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
//////////                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
//////////            );
//////////            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
//////////            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your question about this document...");
//////////
//////////            try {
//////////                startActivityForResult(intent, STT_REQUEST_CODE);
//////////            } catch (Exception e) {
//////////                speak("Speech recognition not available.");
//////////            }
//////////        }
//////////
//////////        @Override
//////////        protected void onActivityResult(int req, int res, @Nullable Intent data) {
//////////            super.onActivityResult(req, res, data);
//////////
//////////            if (req == 6001) {
//////////                if (Settings.canDrawOverlays(this)) {
//////////                    startService(new Intent(this, FloatingOverlayService.class));
//////////                } else {
//////////                    speak("Overlay permission needed to display the assistant bubble.");
//////////                }
//////////            }
//////////
//////////            if (req == SCREEN_CAPTURE_REQUEST && res == RESULT_OK && data != null) {
//////////
//////////                Intent svc = new Intent(this, ScreenCaptureService.class);
//////////                svc.putExtra("resultCode", res);
//////////                svc.putExtra("data", data);
//////////
//////////                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//////////                    startForegroundService(svc);
//////////                }
//////////            }
//////////
//////////            if (req == STT_REQUEST_CODE && res == RESULT_OK && data != null) {
//////////                ArrayList<String> speech = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
//////////                if (speech != null && !speech.isEmpty()) {
//////////                    askGemini(speech.get(0));
//////////                }
//////////            }
//////////        }
//////////
//////////        private void askGemini(String question) {
//////////
//////////            String prompt =
//////////                    "You are a helpful assistant for visually impaired users.\n"
//////////                            + "Here is the scanned text:\n\n"
//////////                            + extractedText
//////////                            + "\n\nUser's question: "
//////////                            + question
//////////                            + "\n\nGive a short, clear, spoken-friendly answer (no asterisks).";
//////////
//////////            Content content = new Content.Builder()
//////////                    .addText(prompt)
//////////                    .build();
//////////
//////////            ListenableFuture<GenerateContentResponse> future =
//////////                    gemini.generateContent(content);
//////////
//////////            Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
//////////                @Override
//////////                public void onSuccess(GenerateContentResponse response) {
//////////
//////////                    String txt = response.getText();
//////////                    if (txt == null) txt = "I could not understand the document.";
//////////
//////////                    String cleaned = txt.replace("*", "");
//////////
//////////                    String finalText = cleaned;
//////////
//////////                    uiHandler.post(() -> {
//////////                        answerText.setText(finalText);
//////////                        speak(finalText);
//////////                    });
//////////                }
//////////
//////////                @Override
//////////                public void onFailure(Throwable t) {
//////////                    Log.e("Gemini Error", t.getMessage());
//////////    //                Toast.makeText(MainActivity.this, "" + t.getMessage(), Toast.LENGTH_SHORT).show();
//////////                    uiHandler.post(() -> speak("Gemini is unavailable right now."));
//////////                }
//////////
//////////            }, executor);
//////////        }
//////////
//////////        private void speak(String text) {
//////////            if (tts != null && text != null && !text.isEmpty()) {
//////////                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_doc");
//////////            }
//////////        }
//////////
//////////        @Override
//////////        protected void onDestroy() {
//////////            super.onDestroy();
//////////            if (tts != null) {
//////////                tts.stop();
//////////                tts.shutdown();
//////////            }
//////////        }
//////////
//////////        @Override
//////////        public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
//////////            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
//////////
//////////            if (requestCode == 7001) {
//////////                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//////////                    Log.d("PERM", "CAPTURE_VIDEO_OUTPUT granted");
//////////                } else {
//////////                    Toast.makeText(this,
//////////                            "Screen capture permission is required.",
//////////                            Toast.LENGTH_LONG).show();
//////////                }
//////////            }
//////////        }
//////////
//////////    }
//////////
//////////
//////////    //import android.Manifest;
//////////    //import android.content.Intent;
//////////    //import android.content.pm.PackageManager;
//////////    //import android.graphics.Bitmap;
//////////    //import android.graphics.BitmapFactory;
//////////    //import android.net.Uri;
//////////    //import android.os.Bundle;
//////////    //import android.os.Handler;
//////////    //import android.os.Looper;
//////////    //import android.speech.RecognizerIntent;
//////////    //import android.speech.tts.TextToSpeech;
//////////    //import android.util.Log;
//////////    //import android.widget.Button;
//////////    //import android.widget.TextView;
//////////    //import android.widget.Toast;
//////////    //
//////////    //import androidx.activity.result.ActivityResultLauncher;
//////////    //import androidx.activity.result.IntentSenderRequest;
//////////    //import androidx.activity.result.contract.ActivityResultContracts;
//////////    //import androidx.annotation.Nullable;
//////////    //import androidx.appcompat.app.AppCompatActivity;
//////////    //
//////////    //import com.google.ai.client.generativeai.GenerativeModel;
//////////    //import com.google.ai.client.generativeai.java.GenerativeModelFutures;
//////////    //import com.google.ai.client.generativeai.type.Content;
//////////    //import com.google.ai.client.generativeai.type.GenerateContentResponse;
//////////    //import com.google.android.gms.tasks.Task;
//////////    //import com.google.common.util.concurrent.FutureCallback;
//////////    //import com.google.common.util.concurrent.Futures;
//////////    //import com.google.common.util.concurrent.ListenableFuture;
//////////    //import com.google.mlkit.vision.common.InputImage;
//////////    //import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
//////////    //import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
//////////    //import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
//////////    //import com.google.mlkit.vision.text.TextRecognition;
//////////    //import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
//////////    //import com.google.mlkit.vision.text.Text;
//////////    //
//////////    //import java.io.InputStream;
//////////    //import java.util.ArrayList;
//////////    //import java.util.Locale;
//////////    //import java.util.concurrent.Executor;
//////////    //import java.util.concurrent.Executors;
//////////    //
//////////    //public class MainActivity extends AppCompatActivity {
//////////    //
//////////    //    private TextView statusText, answerText;
//////////    //    private Button scanButton, askButton;
//////////    //    private TextToSpeech tts;
//////////    //
//////////    //    private String extractedText = "";
//////////    //
//////////    //    private final Executor executor = Executors.newSingleThreadExecutor();
//////////    //    private final Handler uiHandler = new Handler(Looper.getMainLooper());
//////////    //
//////////    //    private static final int STT_REQUEST_CODE = 5001;
//////////    //
//////////    //    // Gemini
//////////    //    private GenerativeModelFutures gemini;
//////////    //
//////////    //    // Document scanner options
//////////    //    private final GmsDocumentScannerOptions scanOptions = new GmsDocumentScannerOptions.Builder()
//////////    //            .setGalleryImportAllowed(true)
//////////    //            .setPageLimit(1)
//////////    //            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
//////////    //            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
//////////    //            .build();
//////////    //
//////////    //    ActivityResultLauncher<IntentSenderRequest> scanLauncher;
//////////    //    ActivityResultLauncher<String> micPermissionLauncher;
//////////    //
//////////    //    @Override
//////////    //    protected void onCreate(Bundle savedInstanceState) {
//////////    //        super.onCreate(savedInstanceState);
//////////    //        setContentView(R.layout.activity_main);
//////////    //
//////////    //        statusText = findViewById(R.id.statusText);
//////////    //        answerText = findViewById(R.id.answerText);
//////////    //        scanButton = findViewById(R.id.scanButton);
//////////    //        askButton = findViewById(R.id.askButton);
//////////    //
//////////    //        initTTS();
//////////    //        initGemini();
//////////    //        setupLaunchers();
//////////    //
//////////    //        scanButton.setOnClickListener(v -> startDocumentScan());
//////////    //        askButton.setOnClickListener(v -> startSpeechToText());
//////////    //    }
//////////    //
//////////    //
//////////    //    private void initGemini() {
//////////    //        GenerativeModel gm = new GenerativeModel(
//////////    //                "gemini-2.0-flash",
//////////    //                "AIzaSyAFkx80WDrFNs7UqwKws9fFhkzrtM21Ejk"
//////////    //        );
//////////    //        gemini = GenerativeModelFutures.from(gm);
//////////    //    }
//////////    //
//////////    //    private void initTTS() {
//////////    //        tts = new TextToSpeech(this, status -> {
//////////    //            if (status == TextToSpeech.SUCCESS) {
//////////    //                tts.setLanguage(Locale.getDefault());
//////////    //                tts.setSpeechRate(1.0f);
//////////    //            }
//////////    //        });
//////////    //    }
//////////    //
//////////    //    private void setupLaunchers() {
//////////    //
//////////    //        scanLauncher = registerForActivityResult(
//////////    //                new ActivityResultContracts.StartIntentSenderForResult(),
//////////    //                result -> {
//////////    //                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
//////////    //                        GmsDocumentScanningResult r =
//////////    //                                GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
//////////    //
//////////    //                        handleDocumentScan(r);
//////////    //                    }
//////////    //                }
//////////    //        );
//////////    //
//////////    //        micPermissionLauncher =
//////////    //                registerForActivityResult(new ActivityResultContracts.RequestPermission(),
//////////    //                        granted -> {
//////////    //                            if (!granted)
//////////    //                                speak("Microphone permission needed.");
//////////    //                        });
//////////    //    }
//////////    //
//////////    //    private void startDocumentScan() {
//////////    //        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
//////////    //                != PackageManager.PERMISSION_GRANTED) {
//////////    //            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
//////////    //        }
//////////    //
//////////    //        GmsDocumentScanning.getClient(scanOptions)
//////////    //                .getStartScanIntent(this)
//////////    //                .addOnSuccessListener(intentSender -> {
//////////    //                    IntentSenderRequest req = new IntentSenderRequest.Builder(intentSender).build();
//////////    //                    scanLauncher.launch(req);
//////////    //                })
//////////    //                .addOnFailureListener(e -> speak("Unable to open document scanner."));
//////////    //    }
//////////    //
//////////    //    private void handleDocumentScan(GmsDocumentScanningResult result) {
//////////    //        if (result == null || result.getPages() == null || result.getPages().isEmpty()) {
//////////    //            speak("Scan failed.");
//////////    //            return;
//////////    //        }
//////////    //
//////////    //        Uri uri = result.getPages().get(0).getImageUri();
//////////    //        runOCR(uri);
//////////    //    }
//////////    //
//////////    //    private void runOCR(Uri uri) {
//////////    //        try {
//////////    //            InputStream is = getContentResolver().openInputStream(uri);
//////////    //            Bitmap bitmap = BitmapFactory.decodeStream(is);
//////////    //            is.close();
//////////    //
//////////    //            InputImage image = InputImage.fromBitmap(bitmap, 0);
//////////    //
//////////    //            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
//////////    //                    .process(image)
//////////    //                    .addOnSuccessListener(text -> {
//////////    //                        extractedText = text.getText();
//////////    //                        speak("Document ready. Ask your question.");
//////////    //                        statusText.setText("Document loaded. Ready for questions.");
//////////    //                    })
//////////    //                    .addOnFailureListener(e -> speak("OCR failed."));
//////////    //
//////////    //        } catch (Exception e) {
//////////    //            speak("Error reading scanned image.");
//////////    //        }
//////////    //    }
//////////    //
//////////    //    private void startSpeechToText() {
//////////    //
//////////    //        if (extractedText.isEmpty()) {
//////////    //            speak("Please scan a document first.");
//////////    //            return;
//////////    //        }
//////////    //
//////////    //        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
//////////    //        intent.putExtra(
//////////    //                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
//////////    //                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
//////////    //        );
//////////    //        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
//////////    //        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your question about this document...");
//////////    //
//////////    //        try {
//////////    //            startActivityForResult(intent, STT_REQUEST_CODE);
//////////    //        } catch (Exception e) {
//////////    //            speak("Speech recognition not available.");
//////////    //        }
//////////    //    }
//////////    //
//////////    //    @Override
//////////    //    protected void onActivityResult(int req, int res, @Nullable Intent data) {
//////////    //        super.onActivityResult(req, res, data);
//////////    //        if (req == STT_REQUEST_CODE && res == RESULT_OK && data != null) {
//////////    //            ArrayList<String> speech = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
//////////    //            if (speech != null && !speech.isEmpty()) {
//////////    //                askGemini(speech.get(0));
//////////    //            }
//////////    //        }
//////////    //    }
//////////    //
//////////    //    private void askGemini(String question) {
//////////    //
//////////    //        String prompt =
//////////    //                "You are a helpful assistant for visually impaired users.\n"
//////////    //                        + "Here is the scanned text:\n\n"
//////////    //                        + extractedText
//////////    //                        + "\n\nUser's question: "
//////////    //                        + question
//////////    //                        + "\n\nGive a short, clear, spoken-friendly answer (no asterisks).";
//////////    //
//////////    //        Content content = new Content.Builder()
//////////    //                .addText(prompt)
//////////    //                .build();
//////////    //
//////////    //        ListenableFuture<GenerateContentResponse> future =
//////////    //                gemini.generateContent(content);
//////////    //
//////////    //        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
//////////    //            @Override
//////////    //            public void onSuccess(GenerateContentResponse response) {
//////////    //
//////////    //                String txt = response.getText();
//////////    //                if (txt == null) txt = "I could not understand the document.";
//////////    //
//////////    //                String cleaned = txt.replace("*", "");
//////////    //
//////////    //                String finalText = cleaned;
//////////    //
//////////    //                uiHandler.post(() -> {
//////////    //                    answerText.setText(finalText);
//////////    //                    speak(finalText);
//////////    //                });
//////////    //            }
//////////    //
//////////    //            @Override
//////////    //            public void onFailure(Throwable t) {
//////////    //                Toast.makeText(MainActivity.this, "" + t.getMessage(), Toast.LENGTH_SHORT).show();
//////////    //                uiHandler.post(() -> speak("Gemini is unavailable right now."));
//////////    //            }
//////////    //
//////////    //        }, executor);
//////////    //    }
//////////    //
//////////    //
//////////    //    private void speak(String text) {
//////////    //        if (tts != null && text != null && !text.isEmpty()) {
//////////    //            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_doc");
//////////    //        }
//////////    //    }
//////////    //
//////////    //    @Override
//////////    //    protected void onDestroy() {
//////////    //        super.onDestroy();
//////////    //        if (tts != null) {
//////////    //            tts.stop();
//////////    //            tts.shutdown();
//////////    //        }
//////////    //    }
//////////    //}
////////
////////package com.example.textassist;
////////
////////import android.Manifest;
////////import android.content.Intent;
////////import android.content.pm.PackageManager;
////////import android.graphics.Bitmap;
////////import android.graphics.BitmapFactory;
////////import android.net.Uri;
////////import android.os.Build;
////////import android.os.Bundle;
////////import android.os.Handler;
////////import android.os.Looper;
////////import android.provider.Settings;
////////import android.speech.RecognizerIntent;
////////import android.speech.tts.TextToSpeech;
////////import android.util.Log;
////////import android.widget.Button;
////////import android.widget.TextView;
////////import android.widget.Toast;
////////
////////import androidx.activity.result.ActivityResultLauncher;
////////import androidx.activity.result.IntentSenderRequest;
////////import androidx.activity.result.contract.ActivityResultContracts;
////////import androidx.annotation.Nullable;
////////import androidx.appcompat.app.AppCompatActivity;
////////
////////import com.google.ai.client.generativeai.GenerativeModel;
////////import com.google.ai.client.generativeai.java.GenerativeModelFutures;
////////import com.google.ai.client.generativeai.type.Content;
////////import com.google.ai.client.generativeai.type.GenerateContentResponse;
////////import com.google.common.util.concurrent.FutureCallback;
////////import com.google.common.util.concurrent.Futures;
////////import com.google.common.util.concurrent.ListenableFuture;
////////import com.google.mlkit.vision.common.InputImage;
////////import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
////////import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
////////import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
////////import com.google.mlkit.vision.text.TextRecognition;
////////import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
////////
////////import java.io.InputStream;
////////import java.util.ArrayList;
////////import java.util.Locale;
////////import java.util.concurrent.Executor;
////////import java.util.concurrent.Executors;
////////
////////import android.media.projection.MediaProjectionManager;
////////
////////import android.provider.Settings;
////////import android.media.projection.MediaProjectionManager;
////////import android.content.Context;
////////
////////public class MainActivity extends AppCompatActivity {
////////
////////    private TextView statusText, answerText;
////////    private Button scanButton, askButton;
////////    private TextToSpeech tts;
////////
////////    private String extractedText = "";
////////
////////    private final Executor executor = Executors.newSingleThreadExecutor();
////////    private final Handler uiHandler = new Handler(Looper.getMainLooper());
////////
////////    private static final int STT_REQUEST_CODE = 5001;
////////    private static final int SCREEN_CAPTURE_REQUEST = 9001;
////////    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1001;
////////
////////    private GenerativeModelFutures gemini;
////////
////////    private final GmsDocumentScannerOptions scanOptions = new GmsDocumentScannerOptions.Builder()
////////            .setGalleryImportAllowed(true)
////////            .setPageLimit(1)
////////            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
////////            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
////////            .build();
////////
////////    ActivityResultLauncher<IntentSenderRequest> scanLauncher;
////////    ActivityResultLauncher<String> micPermissionLauncher;
////////
////////    @Override
////////    protected void onCreate(Bundle savedInstanceState) {
////////        super.onCreate(savedInstanceState);
////////        setContentView(R.layout.activity_main);
////////
////////        // Overlay permission
////////        if (!Settings.canDrawOverlays(this)) {
////////            Intent intent = new Intent(
////////                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
////////                    Uri.parse("package:" + getPackageName())
////////            );
////////            startActivityForResult(intent, 6001);
////////            return;
////////        }
////////
////////        // UI references
////////        statusText = findViewById(R.id.statusText);
////////        answerText = findViewById(R.id.answerText);
////////        scanButton = findViewById(R.id.scanButton);
////////        askButton = findViewById(R.id.askButton);
////////
////////        initTTS();
////////        initGemini();
////////        setupLaunchers();
////////
////////        scanButton.setOnClickListener(v -> startDocumentScan());
////////        askButton.setOnClickListener(v -> startSpeechToText());
////////
////////        Button shareBtn = findViewById(R.id.shareBtn);
////////        shareBtn.setOnClickListener(v -> {
////////            if (!Settings.canDrawOverlays(this)) {
////////                // Request Overlay Permission
////////                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
////////                        Uri.parse("package:" + getPackageName()));
////////                startActivity(intent);
////////            } else {
////////                startScreenShare();
////////            }
////////        });    }
////////
////////    private void initGemini() {
////////        GenerativeModel gm = new GenerativeModel(
////////                "gemini-2.0-flash",
////////                "YOUR_API_KEY"
////////        );
////////        gemini = GenerativeModelFutures.from(gm);
////////    }
////////
////////    private void startScreenShare() {
////////        MediaProjectionManager manager =
////////                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
////////        startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE);
////////    }
////////
////////    // SCREEN CAPTURE REQUEST
////////    private void requestScreenShare() {
////////        MediaProjectionManager mgr =
////////                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
////////
////////        Intent intent = mgr.createScreenCaptureIntent();
////////        startActivityForResult(intent, SCREEN_CAPTURE_REQUEST);
////////    }
////////
////////    private void initTTS() {
////////        tts = new TextToSpeech(this, status -> {
////////            if (status == TextToSpeech.SUCCESS) {
////////                tts.setLanguage(Locale.getDefault());
////////                tts.setSpeechRate(1.0f);
////////            }
////////        });
////////    }
////////
////////    private void setupLaunchers() {
////////
////////        scanLauncher = registerForActivityResult(
////////                new ActivityResultContracts.StartIntentSenderForResult(),
////////                result -> {
////////                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
////////                        GmsDocumentScanningResult r =
////////                                GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
////////
////////                        handleDocumentScan(r);
////////                    }
////////                }
////////        );
////////
////////        micPermissionLauncher =
////////                registerForActivityResult(new ActivityResultContracts.RequestPermission(),
////////                        granted -> {
////////                            if (!granted)
////////                                speak("Microphone permission needed.");
////////                        });
////////    }
////////
////////    private void startDocumentScan() {
////////        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
////////                != PackageManager.PERMISSION_GRANTED) {
////////            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
////////        }
////////
////////        GmsDocumentScanning.getClient(scanOptions)
////////                .getStartScanIntent(this)
////////                .addOnSuccessListener(intentSender -> {
////////                    IntentSenderRequest req = new IntentSenderRequest.Builder(intentSender).build();
////////                    scanLauncher.launch(req);
////////                })
////////                .addOnFailureListener(e -> speak("Unable to open document scanner."));
////////    }
////////
////////    private void handleDocumentScan(GmsDocumentScanningResult result) {
////////        if (result == null || result.getPages() == null || result.getPages().isEmpty()) {
////////            speak("Scan failed.");
////////            return;
////////        }
////////
////////        Uri uri = result.getPages().get(0).getImageUri();
////////        runOCR(uri);
////////    }
////////
////////    private void runOCR(Uri uri) {
////////        try {
////////            InputStream is = getContentResolver().openInputStream(uri);
////////            Bitmap bitmap = BitmapFactory.decodeStream(is);
////////            is.close();
////////
////////            InputImage image = InputImage.fromBitmap(bitmap, 0);
////////
////////            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
////////                    .process(image)
////////                    .addOnSuccessListener(text -> {
////////
////////                        extractedText = text.getText();
////////                        speak("Document ready. Ask your question.");
////////                        statusText.setText("Document loaded. Ready for questions.");
////////
////////                    })
////////                    .addOnFailureListener(e -> speak("OCR failed."));
////////        } catch (Exception e) {
////////            speak("Error reading scanned image.");
////////        }
////////    }
////////
////////    private void startSpeechToText() {
////////
////////        if (extractedText.isEmpty()) {
////////            speak("Please scan a document first.");
////////            return;
////////        }
////////
////////        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
////////        intent.putExtra(
////////                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
////////                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
////////        );
////////        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
////////        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your question about this document...");
////////
////////        try {
////////            startActivityForResult(intent, STT_REQUEST_CODE);
////////        } catch (Exception e) {
////////            speak("Speech recognition not available.");
////////        }
////////    }
////////
////////    // RESULT HANDLER
////////    @Override
////////    protected void onActivityResult(int req, int res, @Nullable Intent data) {
////////        super.onActivityResult(req, res, data);
////////
////////        if (req == 6001) {
////////            if (Settings.canDrawOverlays(this)) {
////////                startService(new Intent(this, FloatingOverlayService.class));
////////            } else {
////////                speak("Overlay permission needed to display the assistant bubble.");
////////            }
////////        }
////////
////////        if (req == STT_REQUEST_CODE && res == RESULT_OK && data != null) {
////////            ArrayList<String> speech = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
////////            if (speech != null && !speech.isEmpty()) {
////////                askGemini(speech.get(0));
////////            }
////////        }
////////
////////        if (req == SCREEN_CAPTURE_REQUEST && res == RESULT_OK && data != null) {
////////
////////            Intent svc = new Intent(this, ScreenCaptureService.class);
////////            svc.putExtra("resultCode", res);
////////            svc.putExtra("data", data);
////////
////////            // MUST be startService(), not startForegroundService()
////////            startService(svc);
////////        }
////////
//////////        if (req == STT_REQUEST_CODE && res == RESULT_OK && data != null) {
//////////            ArrayList<String> speech = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
//////////            if (speech != null && !speech.isEmpty()) {
//////////                askGemini(speech.get(0));
//////////            }
//////////        }
////////        if (req == SCREEN_CAPTURE_REQUEST_CODE) {
////////            if (res == RESULT_OK && data != null) {
////////                Intent serviceIntent = new Intent(this, ScreenAssistService.class);
////////                serviceIntent.putExtra("RESULT_CODE", res);
////////                serviceIntent.putExtra("DATA", data);
////////
////////                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
////////                    startForegroundService(serviceIntent);
////////                } else {
////////                    startService(serviceIntent);
////////                }
////////                moveTaskToBack(true); // Minimize app so user sees their screen
////////            } else {
////////                speak("Screen permission denied.");
////////            }
////////        }
////////    }
////////
////////    private void askGemini(String question) {
////////
////////        String prompt =
////////                "You are a helpful assistant for visually impaired users.\n"
////////                        + "Here is the scanned text:\n\n"
////////                        + extractedText
////////                        + "\n\nUser's question: "
////////                        + question
////////                        + "\n\nGive a short, clear, spoken-friendly answer (no asterisks).";
////////
////////        Content content = new Content.Builder()
////////                .addText(prompt)
////////                .build();
////////
////////        ListenableFuture<GenerateContentResponse> future =
////////                gemini.generateContent(content);
////////
////////        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
////////            @Override
////////            public void onSuccess(GenerateContentResponse response) {
////////
////////                String txt = response.getText();
////////                if (txt == null) txt = "I could not understand the document.";
////////
////////                String cleaned = txt.replace("*", "");
////////
////////                uiHandler.post(() -> {
////////                    answerText.setText(cleaned);
////////                    speak(cleaned);
////////                });
////////            }
////////
////////            @Override
////////            public void onFailure(Throwable t) {
////////                uiHandler.post(() -> speak("Gemini is unavailable right now."));
////////            }
////////
////////        }, executor);
////////    }
////////
////////    private void speak(String text) {
////////        if (tts != null && text != null && !text.isEmpty()) {
////////            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_doc");
////////        }
////////    }
////////
////////    @Override
////////    protected void onDestroy() {
////////        super.onDestroy();
////////        if (tts != null) {
////////            tts.stop();
////////            tts.shutdown();
////////        }
////////    }
////////}
//////
//////package com.example.textassist;
//////
//////import android.Manifest;
//////import android.content.Context;
//////import android.content.Intent;
//////import android.content.pm.PackageManager;
//////import android.graphics.Bitmap;
//////import android.graphics.BitmapFactory;
//////import android.media.projection.MediaProjectionManager;
//////import android.net.Uri;
//////import android.os.Build;
//////import android.os.Bundle;
//////import android.os.Handler;
//////import android.os.Looper;
//////import android.provider.Settings;
//////import android.speech.RecognizerIntent;
//////import android.speech.tts.TextToSpeech;
//////import android.util.Log;
//////import android.widget.Button;
//////import android.widget.TextView;
//////import android.widget.Toast;
//////
//////import androidx.activity.result.ActivityResultLauncher;
//////import androidx.activity.result.IntentSenderRequest;
//////import androidx.activity.result.contract.ActivityResultContracts;
//////import androidx.annotation.Nullable;
//////import androidx.appcompat.app.AppCompatActivity;
//////
//////import com.google.ai.client.generativeai.GenerativeModel;
//////import com.google.ai.client.generativeai.java.GenerativeModelFutures;
//////import com.google.ai.client.generativeai.type.Content;
//////import com.google.ai.client.generativeai.type.GenerateContentResponse;
//////import com.google.common.util.concurrent.FutureCallback;
//////import com.google.common.util.concurrent.Futures;
//////import com.google.common.util.concurrent.ListenableFuture;
//////import com.google.mlkit.vision.common.InputImage;
//////import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
//////import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
//////import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
//////import com.google.mlkit.vision.text.TextRecognition;
//////import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
//////
//////import java.io.InputStream;
//////import java.util.ArrayList;
//////import java.util.Locale;
//////import java.util.concurrent.Executor;
//////import java.util.concurrent.Executors;
//////
//////public class MainActivity extends AppCompatActivity {
//////
//////    // UI Components
//////    private TextView statusText, answerText;
//////    private Button scanButton, askButton, screenShareButton;
//////
//////    // Logic & State
//////    private TextToSpeech tts;
//////    private GenerativeModelFutures gemini;
//////    private String extractedText = "";
//////
//////    // Threading
//////    private final Executor executor = Executors.newSingleThreadExecutor();
//////    private final Handler uiHandler = new Handler(Looper.getMainLooper());
//////
//////    // Request Codes
//////    private static final int STT_REQUEST_CODE = 5001;
//////    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1001;
//////
//////    // Launchers
//////    private ActivityResultLauncher<IntentSenderRequest> scanLauncher;
//////    private ActivityResultLauncher<String> micPermissionLauncher;
//////
//////    // Scanner Options
//////    private final GmsDocumentScannerOptions scanOptions = new GmsDocumentScannerOptions.Builder()
//////            .setGalleryImportAllowed(true)
//////            .setPageLimit(1)
//////            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
//////            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
//////            .build();
//////
//////    @Override
//////    protected void onCreate(Bundle savedInstanceState) {
//////        super.onCreate(savedInstanceState);
//////        setContentView(R.layout.activity_main);
//////
//////        // Bind UI
//////        statusText = findViewById(R.id.statusText);
//////        answerText = findViewById(R.id.answerText);
//////        scanButton = findViewById(R.id.scanButton);
//////        askButton = findViewById(R.id.askButton);
//////        screenShareButton = findViewById(R.id.shareBtn); // Ensure this ID exists in XML
//////
//////        // Initialize Systems
//////        initTTS();
//////        initGemini();
//////        setupLaunchers();
//////
//////        // Set Click Listeners
//////        scanButton.setOnClickListener(v -> startDocumentScan());
//////        askButton.setOnClickListener(v -> startSpeechToText());
//////        screenShareButton.setOnClickListener(v -> attemptStartScreenShare());
//////    }
//////
//////    private void initGemini() {
//////        // Use your actual API Key here
//////        GenerativeModel gm = new GenerativeModel(
//////                "gemini-2.0-flash",
//////                "AIzaSyA7nky9qXlTAIxakTI3M9kcFEjo64zDN1A"
//////        );
//////        gemini = GenerativeModelFutures.from(gm);
//////    }
//////
//////    private void initTTS() {
//////        tts = new TextToSpeech(this, status -> {
//////            if (status == TextToSpeech.SUCCESS) {
//////                tts.setLanguage(Locale.getDefault());
//////                tts.setSpeechRate(1.0f);
//////            }
//////        });
//////    }
//////
//////    private void setupLaunchers() {
//////        // Launcher for Document Scanner
//////        scanLauncher = registerForActivityResult(
//////                new ActivityResultContracts.StartIntentSenderForResult(),
//////                result -> {
//////                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
//////                        GmsDocumentScanningResult r =
//////                                GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
//////                        handleDocumentScan(r);
//////                    }
//////                }
//////        );
//////
//////        // Launcher for Mic Permission
//////        micPermissionLauncher = registerForActivityResult(
//////                new ActivityResultContracts.RequestPermission(),
//////                granted -> {
//////                    if (!granted) speak("Microphone permission needed.");
//////                });
//////    }
//////
//////    // ==========================================
//////    // SECTION 1: DOCUMENT SCANNING & OCR
//////    // ==========================================
//////
//////    private void startDocumentScan() {
//////        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
//////            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
//////            return;
//////        }
//////
//////        GmsDocumentScanning.getClient(scanOptions)
//////                .getStartScanIntent(this)
//////                .addOnSuccessListener(intentSender -> {
//////                    IntentSenderRequest req = new IntentSenderRequest.Builder(intentSender).build();
//////                    scanLauncher.launch(req);
//////                })
//////                .addOnFailureListener(e -> speak("Unable to open document scanner."));
//////    }
//////
//////    private void handleDocumentScan(GmsDocumentScanningResult result) {
//////        if (result == null || result.getPages() == null || result.getPages().isEmpty()) {
//////            speak("Scan failed.");
//////            return;
//////        }
//////        Uri uri = result.getPages().get(0).getImageUri();
//////        runOCR(uri);
//////    }
//////
//////    private void runOCR(Uri uri) {
//////        try {
//////            InputStream is = getContentResolver().openInputStream(uri);
//////            Bitmap bitmap = BitmapFactory.decodeStream(is);
//////            is.close();
//////
//////            InputImage image = InputImage.fromBitmap(bitmap, 0);
//////
//////            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
//////                    .process(image)
//////                    .addOnSuccessListener(text -> {
//////                        extractedText = text.getText();
//////                        speak("Document ready. Ask your question.");
//////                        statusText.setText("Document loaded. Ready.");
//////                    })
//////                    .addOnFailureListener(e -> speak("OCR failed."));
//////
//////        } catch (Exception e) {
//////            speak("Error reading scanned image.");
//////        }
//////    }
//////
//////    private void startSpeechToText() {
//////        if (extractedText.isEmpty()) {
//////            speak("Please scan a document first.");
//////            return;
//////        }
//////
//////        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
//////        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
//////        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
//////        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about the document...");
//////
//////        try {
//////            startActivityForResult(intent, STT_REQUEST_CODE);
//////        } catch (Exception e) {
//////            speak("Speech recognition not available.");
//////        }
//////    }
//////
//////    // ==========================================
//////    // SECTION 2: SCREEN SHARE ASSISTANT
//////    // ==========================================
//////
//////    private void attemptStartScreenShare() {
//////        // 1. Check Overlay Permission
//////        if (!Settings.canDrawOverlays(this)) {
//////            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
//////                    Uri.parse("package:" + getPackageName()));
//////            startActivity(intent);
//////            Toast.makeText(this, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show();
//////            return;
//////        }
//////
//////        // 2. Check Microphone Permission (Required for Service)
//////        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
//////            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
//////            Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_SHORT).show();
//////            return;
//////        }
//////
//////        // 3. Start Media Projection
//////        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
//////        startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE);
//////    }
//////
//////    // ==========================================
//////    // SECTION 3: RESULT HANDLING & GEMINI
//////    // ==========================================
//////
//////    @Override
//////    protected void onActivityResult(int req, int res, @Nullable Intent data) {
//////        super.onActivityResult(req, res, data);
//////
//////        // Handle Document Question (Speech)
//////        if (req == STT_REQUEST_CODE && res == RESULT_OK && data != null) {
//////            ArrayList<String> speech = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
//////            if (speech != null && !speech.isEmpty()) {
//////                askGemini(speech.get(0));
//////            }
//////        }
//////
//////        // Handle Screen Share Permission
//////        if (req == SCREEN_CAPTURE_REQUEST_CODE) {
//////            if (res == RESULT_OK && data != null) {
//////                // Permission Granted -> Start Foreground Service
//////                Intent serviceIntent = new Intent(this, ScreenAssistService.class);
//////                serviceIntent.putExtra("RESULT_CODE", res);
//////                serviceIntent.putExtra("DATA", data);
//////
//////                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//////                    startForegroundService(serviceIntent);
//////                } else {
//////                    startService(serviceIntent);
//////                }
//////
//////                // Move app to background so user sees the screen they want to capture
//////                moveTaskToBack(true);
//////            } else {
//////                speak("Screen permission denied.");
//////            }
//////        }
//////    }
//////
//////    private void askGemini(String question) {
//////        String prompt = "You are a helpful assistant for visually impaired users.\n"
//////                + "Here is the scanned text:\n\n" + extractedText
//////                + "\n\nUser's question: " + question
//////                + "\n\nGive a short, clear, spoken-friendly answer (no asterisks).";
//////
//////        Content content = new Content.Builder().addText(prompt).build();
//////        ListenableFuture<GenerateContentResponse> future = gemini.generateContent(content);
//////
//////        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
//////            @Override
//////            public void onSuccess(GenerateContentResponse response) {
//////                String txt = response.getText();
//////                if (txt == null) txt = "I could not understand.";
//////                String finalText = txt.replace("*", ""); // Clean for TTS
//////
//////                uiHandler.post(() -> {
//////                    answerText.setText(finalText);
//////                    speak(finalText);
//////                });
//////            }
//////
//////            @Override
//////            public void onFailure(Throwable t) {
//////                uiHandler.post(() -> speak("Gemini is unavailable."));
//////                Log.e("Gemini", t.getMessage());
//////            }
//////        }, executor);
//////    }
//////
//////    private void speak(String text) {
//////        if (tts != null && text != null && !text.isEmpty()) {
//////            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_main");
//////        }
//////    }
//////
//////    @Override
//////    protected void onDestroy() {
//////        super.onDestroy();
//////        if (tts != null) {
//////            tts.stop();
//////            tts.shutdown();
//////        }
//////    }
//////}
////
////package com.example.textassist;
////
////import android.Manifest;
////import android.content.Context;
////import android.content.Intent;
////import android.content.pm.PackageManager;
////import android.graphics.Bitmap;
////import android.graphics.BitmapFactory;
////import android.media.projection.MediaProjectionManager;
////import android.net.Uri;
////import android.os.Build;
////import android.os.Bundle;
////import android.os.Handler;
////import android.os.Looper;
////import android.provider.Settings;
////import android.speech.RecognizerIntent;
////import android.speech.tts.TextToSpeech;
////import android.util.Log;
////import android.widget.Button;
////import android.widget.TextView;
////import android.widget.Toast;
////
////import androidx.activity.result.ActivityResultLauncher;
////import androidx.activity.result.IntentSenderRequest;
////import androidx.activity.result.contract.ActivityResultContracts;
////import androidx.annotation.Nullable;
////import androidx.appcompat.app.AppCompatActivity;
////
////import com.google.ai.client.generativeai.GenerativeModel;
////import com.google.ai.client.generativeai.java.GenerativeModelFutures;
////import com.google.ai.client.generativeai.type.Content;
////import com.google.ai.client.generativeai.type.GenerateContentResponse;
////import com.google.common.util.concurrent.FutureCallback;
////import com.google.common.util.concurrent.Futures;
////import com.google.common.util.concurrent.ListenableFuture;
////import com.google.mlkit.vision.common.InputImage;
////import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
////import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
////import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
////import com.google.mlkit.vision.text.TextRecognition;
////import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
////
////import java.io.InputStream;
////import java.util.ArrayList;
////import java.util.Locale;
////import java.util.concurrent.Executor;
////import java.util.concurrent.Executors;
////
////public class MainActivity extends AppCompatActivity {
////
////    private TextView statusText, answerText;
////    private Button scanButton, askButton, screenShareButton;
////    private TextToSpeech tts;
////    private String extractedText = "";
////
////    private final Executor executor = Executors.newSingleThreadExecutor();
////    private final Handler uiHandler = new Handler(Looper.getMainLooper());
////
////    private static final int STT_REQUEST_CODE = 5001;
////    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1001;
////
////    private GenerativeModelFutures gemini;
////
////    private final GmsDocumentScannerOptions scanOptions = new GmsDocumentScannerOptions.Builder()
////            .setGalleryImportAllowed(true)
////            .setPageLimit(1)
////            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
////            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
////            .build();
////
////    ActivityResultLauncher<IntentSenderRequest> scanLauncher;
////    ActivityResultLauncher<String> micPermissionLauncher;
////
////    @Override
////    protected void onCreate(Bundle savedInstanceState) {
////        super.onCreate(savedInstanceState);
////        setContentView(R.layout.activity_main);
////
////        statusText = findViewById(R.id.statusText);
////        answerText = findViewById(R.id.answerText);
////        scanButton = findViewById(R.id.scanButton);
////        askButton = findViewById(R.id.askButton);
////        screenShareButton = findViewById(R.id.shareBtn); // Make sure this ID is in XML
////
////        initTTS();
////        initGemini();
////        setupLaunchers();
////
////        scanButton.setOnClickListener(v -> startDocumentScan());
////        askButton.setOnClickListener(v -> startSpeechToText());
////        screenShareButton.setOnClickListener(v -> attemptStartScreenShare());
////    }
////
////    private void initGemini() {
////        GenerativeModel gm = new GenerativeModel(
////                "gemini-2.0-flash",
////                "AIzaSyA7nky9qXlTAIxakTI3M9kcFEjo64zDN1A"
////        );
////        gemini = GenerativeModelFutures.from(gm);
////    }
////
////    private void initTTS() {
////        tts = new TextToSpeech(this, status -> {
////            if (status == TextToSpeech.SUCCESS) {
////                tts.setLanguage(Locale.getDefault());
////                tts.setSpeechRate(1.0f);
////            }
////        });
////    }
////
////    private void setupLaunchers() {
////        scanLauncher = registerForActivityResult(
////                new ActivityResultContracts.StartIntentSenderForResult(),
////                result -> {
////                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
////                        GmsDocumentScanningResult r =
////                                GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
////                        handleDocumentScan(r);
////                    }
////                }
////        );
////
////        micPermissionLauncher = registerForActivityResult(
////                new ActivityResultContracts.RequestPermission(),
////                granted -> {
////                    if (!granted) speak("Microphone permission needed.");
////                });
////    }
////
////    // --- SCREEN SHARE LOGIC ---
////    private void attemptStartScreenShare() {
////        // 1. Overlay
////        if (!Settings.canDrawOverlays(this)) {
////            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
////                    Uri.parse("package:" + getPackageName()));
////            startActivity(intent);
////            Toast.makeText(this, "Enable 'Display over other apps'", Toast.LENGTH_LONG).show();
////            return;
////        }
////
////        // 2. Mic (Critical for Service)
////        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
////            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
////            Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_SHORT).show();
////            return;
////        }
////
////        // 3. Screen
////        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
////        startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE);
////    }
////
////    @Override
////    protected void onActivityResult(int req, int res, @Nullable Intent data) {
////        super.onActivityResult(req, res, data);
////
////        // STT Result
////        if (req == STT_REQUEST_CODE && res == RESULT_OK && data != null) {
////            ArrayList<String> speech = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
////            if (speech != null && !speech.isEmpty()) {
////                askGemini(speech.get(0));
////            }
////        }
////
////        // Screen Share Result
////        if (req == SCREEN_CAPTURE_REQUEST_CODE) {
////            if (res == RESULT_OK && data != null) {
////                Intent serviceIntent = new Intent(this, ScreenAssistService.class);
////                serviceIntent.putExtra("RESULT_CODE", res);
////                serviceIntent.putExtra("DATA", data);
////
////                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
////                    startForegroundService(serviceIntent);
////                } else {
////                    startService(serviceIntent);
////                }
////                moveTaskToBack(true);
////            } else {
////                speak("Screen permission denied.");
////            }
////        }
////    }
////
////    // --- DOCUMENT LOGIC ---
////    private void startDocumentScan() {
////        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
////            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
////            return;
////        }
////        GmsDocumentScanning.getClient(scanOptions)
////                .getStartScanIntent(this)
////                .addOnSuccessListener(intentSender -> {
////                    IntentSenderRequest req = new IntentSenderRequest.Builder(intentSender).build();
////                    scanLauncher.launch(req);
////                })
////                .addOnFailureListener(e -> speak("Scanner error."));
////    }
////
////    private void handleDocumentScan(GmsDocumentScanningResult result) {
////        if (result == null || result.getPages() == null || result.getPages().isEmpty()) {
////            speak("Scan failed.");
////            return;
////        }
////        runOCR(result.getPages().get(0).getImageUri());
////    }
////
////    private void runOCR(Uri uri) {
////        try {
////            InputStream is = getContentResolver().openInputStream(uri);
////            Bitmap bitmap = BitmapFactory.decodeStream(is);
////            is.close();
////            InputImage image = InputImage.fromBitmap(bitmap, 0);
////
////            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
////                    .process(image)
////                    .addOnSuccessListener(text -> {
////                        extractedText = text.getText();
////                        speak("Document ready. Ask your question.");
////                        statusText.setText("Document loaded. Ready.");
////                    })
////                    .addOnFailureListener(e -> speak("OCR failed."));
////        } catch (Exception e) {
////            speak("Error reading image.");
////        }
////    }
////
////    private void startSpeechToText() {
////        if (extractedText.isEmpty()) {
////            speak("Please scan a document first.");
////            return;
////        }
////        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
////        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
////        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
////        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about the document...");
////        try {
////            startActivityForResult(intent, STT_REQUEST_CODE);
////        } catch (Exception e) {
////            speak("Speech recognition error.");
////        }
////    }
////
////    private void askGemini(String question) {
////        String prompt = "You are a helpful assistant.\nScanned text:\n" + extractedText + "\nQuestion: " + question + "\nAnswer briefly.";
////        Content content = new Content.Builder().addText(prompt).build();
////        ListenableFuture<GenerateContentResponse> future = gemini.generateContent(content);
////
////        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
////            @Override
////            public void onSuccess(GenerateContentResponse response) {
////                String txt = response.getText();
////                if (txt != null) {
////                    String clean = txt.replace("*", "");
////                    uiHandler.post(() -> {
////                        answerText.setText(clean);
////                        speak(clean);
////                    });
////                }
////            }
////            @Override
////            public void onFailure(Throwable t) {
////                uiHandler.post(() -> speak("Gemini error."));
////            }
////        }, executor);
////    }
////
////    private void speak(String text) {
////        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_main");
////    }
////
////    @Override
////    protected void onDestroy() {
////        super.onDestroy();
////        if (tts != null) {
////            tts.stop();
////            tts.shutdown();
////        }
////    }
////}
//
//package com.example.textassist;
//
//import android.Manifest;
//import android.content.Context;
//import android.content.Intent;
//import android.content.pm.PackageManager;
//import android.graphics.Bitmap;
//import android.graphics.BitmapFactory;
//import android.media.projection.MediaProjectionManager;
//import android.net.Uri;
//import android.os.Build;
//import android.os.Bundle;
//import android.os.Handler;
//import android.os.Looper;
//import android.provider.Settings;
//import android.speech.RecognizerIntent;
//import android.speech.tts.TextToSpeech;
//import android.util.Log;
//import android.widget.Button;
//import android.widget.TextView;
//import android.widget.Toast;
//
//import androidx.activity.result.ActivityResultLauncher;
//import androidx.activity.result.IntentSenderRequest;
//import androidx.activity.result.contract.ActivityResultContracts;
//import androidx.annotation.Nullable;
//import androidx.appcompat.app.AppCompatActivity;
//
//import com.google.ai.client.generativeai.GenerativeModel;
//import com.google.ai.client.generativeai.java.GenerativeModelFutures;
//import com.google.ai.client.generativeai.type.Content;
//import com.google.ai.client.generativeai.type.GenerateContentResponse;
//import com.google.common.util.concurrent.FutureCallback;
//import com.google.common.util.concurrent.Futures;
//import com.google.common.util.concurrent.ListenableFuture;
//import com.google.mlkit.vision.common.InputImage;
//import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
//import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
//import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
//import com.google.mlkit.vision.text.TextRecognition;
//import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
//
//import java.io.InputStream;
//import java.util.ArrayList;
//import java.util.Locale;
//import java.util.concurrent.Executor;
//import java.util.concurrent.Executors;
//
//public class MainActivity extends AppCompatActivity {
//
//    private TextView statusText, answerText;
//    private Button scanButton, askButton, screenShareButton;
//    private TextToSpeech tts;
//    private String extractedText = "";
//
//    private final Executor executor = Executors.newSingleThreadExecutor();
//    private final Handler uiHandler = new Handler(Looper.getMainLooper());
//
//    private static final int STT_REQUEST_CODE = 5001;
//    private static final int SCREEN_CAPTURE_REQUEST_CODE = 1001;
//
//    private GenerativeModelFutures gemini;
//
//    private final GmsDocumentScannerOptions scanOptions = new GmsDocumentScannerOptions.Builder()
//            .setGalleryImportAllowed(true)
//            .setPageLimit(1)
//            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
//            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
//            .build();
//
//    ActivityResultLauncher<IntentSenderRequest> scanLauncher;
//    ActivityResultLauncher<String> micPermissionLauncher;
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        setContentView(R.layout.activity_main);
//
//        statusText = findViewById(R.id.statusText);
//        answerText = findViewById(R.id.answerText);
//        scanButton = findViewById(R.id.scanButton);
//        askButton = findViewById(R.id.askButton);
//        screenShareButton = findViewById(R.id.shareBtn);
//
//        initTTS();
//        initGemini();
//        setupLaunchers();
//
//        scanButton.setOnClickListener(v -> startDocumentScan());
//        askButton.setOnClickListener(v -> startSpeechToText());
//        screenShareButton.setOnClickListener(v -> attemptStartScreenShare());
//    }
//
//    private void initGemini() {
//        GenerativeModel gm = new GenerativeModel(
//                "gemini-2.0-flash",
//                "AIzaSyA7nky9qXlTAIxakTI3M9kcFEjo64zDN1A"
//        );
//        gemini = GenerativeModelFutures.from(gm);
//    }
//
//    private void initTTS() {
//        tts = new TextToSpeech(this, status -> {
//            if (status == TextToSpeech.SUCCESS) {
//                tts.setLanguage(Locale.getDefault());
//                tts.setSpeechRate(1.0f);
//            }
//        });
//    }
//
//    private void setupLaunchers() {
//        scanLauncher = registerForActivityResult(
//                new ActivityResultContracts.StartIntentSenderForResult(),
//                result -> {
//                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
//                        GmsDocumentScanningResult r =
//                                GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
//                        handleDocumentScan(r);
//                    }
//                }
//        );
//
//        micPermissionLauncher = registerForActivityResult(
//                new ActivityResultContracts.RequestPermission(),
//                granted -> {
//                    if (!granted) speak("Microphone permission needed.");
//                });
//    }
//
//    private void attemptStartScreenShare() {
//        if (!Settings.canDrawOverlays(this)) {
//            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
//                    Uri.parse("package:" + getPackageName()));
//            startActivity(intent);
//            Toast.makeText(this, "Enable 'Display over other apps'", Toast.LENGTH_LONG).show();
//            return;
//        }
//
//        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
//            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
//            Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_SHORT).show();
//            return;
//        }
//
//        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
//        startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE);
//    }
//
//    @Override
//    protected void onActivityResult(int req, int res, @Nullable Intent data) {
//        super.onActivityResult(req, res, data);
//
//        if (req == STT_REQUEST_CODE && res == RESULT_OK && data != null) {
//            ArrayList<String> speech = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
//            if (speech != null && !speech.isEmpty()) {
//                askGemini(speech.get(0));
//            }
//        }
//
//        if (req == SCREEN_CAPTURE_REQUEST_CODE) {
//            if (res == RESULT_OK && data != null) {
//                Intent serviceIntent = new Intent(this, ScreenAssistService.class);
//                serviceIntent.putExtra("RESULT_CODE", res);
//                serviceIntent.putExtra("DATA", data);
//
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                    startForegroundService(serviceIntent);
//                } else {
//                    startService(serviceIntent);
//                }
//                moveTaskToBack(true);
//            } else {
//                speak("Screen permission denied.");
//            }
//        }
//    }
//
//    private void startDocumentScan() {
//        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
//            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
//            return;
//        }
//        GmsDocumentScanning.getClient(scanOptions)
//                .getStartScanIntent(this)
//                .addOnSuccessListener(intentSender -> {
//                    IntentSenderRequest req = new IntentSenderRequest.Builder(intentSender).build();
//                    scanLauncher.launch(req);
//                })
//                .addOnFailureListener(e -> speak("Scanner error."));
//    }
//
//    private void handleDocumentScan(GmsDocumentScanningResult result) {
//        if (result == null || result.getPages() == null || result.getPages().isEmpty()) {
//            speak("Scan failed.");
//            return;
//        }
//        runOCR(result.getPages().get(0).getImageUri());
//    }
//
//    private void runOCR(Uri uri) {
//        try {
//            InputStream is = getContentResolver().openInputStream(uri);
//            Bitmap bitmap = BitmapFactory.decodeStream(is);
//            is.close();
//            InputImage image = InputImage.fromBitmap(bitmap, 0);
//
//            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
//                    .process(image)
//                    .addOnSuccessListener(text -> {
//                        extractedText = text.getText();
//                        speak("Document ready. Ask your question.");
//                        statusText.setText("Document loaded. Ready.");
//                    })
//                    .addOnFailureListener(e -> speak("OCR failed."));
//        } catch (Exception e) {
//            speak("Error reading image.");
//        }
//    }
//
//    private void startSpeechToText() {
//        if (extractedText.isEmpty()) {
//            speak("Please scan a document first.");
//            return;
//        }
//        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
//        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
//        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
//        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about the document...");
//        try {
//            startActivityForResult(intent, STT_REQUEST_CODE);
//        } catch (Exception e) {
//            speak("Speech recognition error.");
//        }
//    }
//
//    private void askGemini(String question) {
//        String prompt = "You are a helpful assistant.\nScanned text:\n" + extractedText + "\nQuestion: " + question + "\nAnswer briefly.";
//        Content content = new Content.Builder().addText(prompt).build();
//        ListenableFuture<GenerateContentResponse> future = gemini.generateContent(content);
//
//        Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
//            @Override
//            public void onSuccess(GenerateContentResponse response) {
//                String txt = response.getText();
//                if (txt != null) {
//                    String clean = txt.replace("*", "");
//                    uiHandler.post(() -> {
//                        answerText.setText(clean);
//                        speak(clean);
//                    });
//                }
//            }
//            @Override
//            public void onFailure(Throwable t) {
//                uiHandler.post(() -> speak("Gemini error."));
//            }
//        }, executor);
//    }
//
//    private void speak(String text) {
//        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_main");
//    }
//
//    @Override
//    protected void onDestroy() {
//        super.onDestroy();
//        if (tts != null) {
//            tts.stop();
//            tts.shutdown();
//        }
//    }
//}

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