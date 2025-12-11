package com.example.textassist;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ScreenAssistService extends Service {

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams params;

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private GenerativeModelFutures gemini;
    private final Executor executor = Executors.newSingleThreadExecutor();

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    private static final String ID_PROMPT_LISTENING = "prompt_listening";

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Init Gemini
        GenerativeModel gm = new GenerativeModel("gemini-2.0-flash", "USE_YOUR_GEMINI_API_KEY");
        gemini = GenerativeModelFutures.from(gm);

        // 2. Init TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.getDefault());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {}

                    @Override
                    public void onDone(String utteranceId) {
                        if (ID_PROMPT_LISTENING.equals(utteranceId)) {
                            new Handler(Looper.getMainLooper()).post(() -> startListeningInternal());
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {}
                });
            }
        });

        // 3. Init Overlay
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createOverlay();

        // 4. Init Speech Recognizer
        new Handler(Looper.getMainLooper()).post(() -> {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createOverlay() {
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 200;

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null);
        ImageButton btn = overlayView.findViewById(R.id.overlayButton);

        btn.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private long startClickTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        startClickTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(overlayView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis() - startClickTime < 200) {
                            startListening();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(overlayView, params);
    }

    private void startListening() {
        speak("Listening...", ID_PROMPT_LISTENING);
    }

    private void startListeningInternal() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override
                public void onError(int error) {
                    if (error == 7) {
                        Toast.makeText(ScreenAssistService.this, "No voice detected", Toast.LENGTH_SHORT).show();
                    } else {
                        speak("Error " + error, null);
                    }
                }
                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        captureAndAsk(matches.get(0));
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            speak("Mic error", null);
        }
    }

    private void captureAndAsk(String question) {
        speak("Thinking...", null);

        if (imageReader == null || mediaProjection == null) {
            Log.e("ScreenAssist", "FATAL: ImageReader is null. Init skipped.");
            speak("Feature not ready. Restart app.", null);
            return;
        }

        try {
            Image image = imageReader.acquireLatestImage();

            if (image == null) {
                speak("Screen not ready. Try again.", null);
                return;
            }

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            image.close();

            Bitmap cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);

            Content content = new Content.Builder()
                    .addImage(cleanBitmap)
                    .addText("User question: " + question + ". Answer briefly.")
                    .build();

            ListenableFuture<GenerateContentResponse> future = gemini.generateContent(content);

            Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
                @Override
                public void onSuccess(GenerateContentResponse result) {
                    String text = result.getText();
                    if(text != null) {
                        speak(text.replace("*", ""), null);
                    } else {
                        speak("No answer found.", null);
                    }
                }
                @Override
                public void onFailure(Throwable t) {
                    Log.e("AI-Error", t.getMessage());
                    speak("AI Error.", null);
                }
            }, executor);

        } catch (Exception e) {
            Log.e("CaptureError", "Details: " + e.getMessage());
            speak("Capture failed.", null);
        }
    }

    private void speak(String text, String utteranceId) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, "screen_assist")
                .setContentTitle("Screen Assistant Active")
                .setContentText("Tap the bubble to ask")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(1, notification, types);
        } else {
            startForeground(1, notification);
        }

        if (intent != null) {
            int resultCode = intent.getIntExtra("RESULT_CODE", 100);
            Intent data = intent.getParcelableExtra("DATA");

            if (resultCode != 100 && data != null) {
                projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                mediaProjection = projectionManager.getMediaProjection(resultCode, data);

                // --- FIX FOR CRASH START ---
                // Android 14+ requires registering a callback BEFORE starting capture
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        if (mediaProjection != null) {
                            mediaProjection = null;
                        }
                    }
                }, null);
                // --- FIX FOR CRASH END ---

                setupVirtualDisplay();
            }
        }

        return START_NOT_STICKY;
    }

    private void setupVirtualDisplay() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics = getResources().getDisplayMetrics();
        }

        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        if(screenWidth == 0) screenWidth = 1080;
        if(screenHeight == 0) screenHeight = 1920;

        if (imageReader != null) imageReader.close();

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    "screen_assist", "Screen Assistant", NotificationManager.IMPORTANCE_DEFAULT);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && overlayView != null) windowManager.removeView(overlayView);
        if (mediaProjection != null) mediaProjection.stop();
        if (tts != null) tts.shutdown();
        if (speechRecognizer != null) speechRecognizer.destroy();
    }
}