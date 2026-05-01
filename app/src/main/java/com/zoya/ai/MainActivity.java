package com.zoya.ai;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MainActivity extends AppCompatActivity
        implements GeminiWebSocketManager.GeminiListener {

    private static final String TAG = "ZoyaMain";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String PREFS_NAME = "zoya_prefs";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_PERSONALITY = "personality";
    private static final String DEFAULT_API_KEY = "YOUR_API_KEY_HERE";
    private static final String DEFAULT_API_URL = "https://fyroplayer.com/zoya-admin/api.php";

    // State
    private enum AppState { IDLE, LISTENING, PROCESSING, SPEAKING }
    private AppState currentState = AppState.IDLE;
    private boolean sessionActive = false;
    private boolean textInputVisible = false;
    private boolean pendingTextMessage = false;
    private String pendingText = null;

    // Managers
    private GeminiWebSocketManager webSocketManager;
    private ZoyaAudioManager audioManager;
    private GeminiTextChatManager textChatManager;
    private GeminiTTSManager ttsManager;

    // Views
    private ZoyaVisualizerView visualizer;
    private RecyclerView chatRecyclerView;
    private ChatAdapter chatAdapter;
    private LinearLayout textInputBar;
    private EditText textInput;
    private TextView btnSession;
    private TextView muteIcon;
    private FrameLayout apiKeyOverlay;
    private EditText apiKeyInput;
    private EditText apiUrlInput;
    private FrameLayout permissionOverlay;
    private LinearLayout listeningIndicator;
    private LinearLayout replyingIndicator;
    private View listeningDot;

    // Listening dot pulse animation
    private ValueAnimator dotPulseAnimator;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // Accumulate text response across streaming chunks
    private final StringBuilder currentResponseText = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        initViews();
        initManagers();
        setupClickListeners();
        setupChatRecyclerView();

        // Always fetch settings from admin panel API
        String apiUrl = prefs.getString(KEY_API_URL, DEFAULT_API_URL);
        if (!TextUtils.isEmpty(apiUrl)) {
            // Clear any old cached key first, then fetch fresh from admin panel
            prefs.edit().remove(KEY_API_KEY).apply();
            fetchSettingsFromApi(apiUrl);
        } else if (!"YOUR_API_KEY_HERE".equals(DEFAULT_API_KEY)) {
            prefs.edit().putString(KEY_API_KEY, DEFAULT_API_KEY).apply();
        }

        // Request mic permission immediately on launch
        requestMicPermission();
    }

    private void initViews() {
        visualizer = findViewById(R.id.visualizer);
        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        textInputBar = findViewById(R.id.textInputBar);
        textInput = findViewById(R.id.textInput);
        btnSession = findViewById(R.id.btnSession);
        muteIcon = findViewById(R.id.muteIcon);
        apiKeyOverlay = findViewById(R.id.apiKeyOverlay);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        apiUrlInput = findViewById(R.id.apiUrlInput);
        permissionOverlay = findViewById(R.id.permissionOverlay);
        listeningIndicator = findViewById(R.id.listeningIndicator);
        replyingIndicator = findViewById(R.id.replyingIndicator);
        listeningDot = findViewById(R.id.listeningDot);
    }

    private void initManagers() {
        // Voice session (WebSocket Live API)
        webSocketManager = new GeminiWebSocketManager();
        webSocketManager.setListener(this);

        // Audio I/O
        audioManager = new ZoyaAudioManager();
        audioManager.setAudioCallback(data -> {
            if (sessionActive) {
                webSocketManager.sendAudioChunk(data);
            }
        });

        // Text chat (REST API - gemini-2.0-flash)
        textChatManager = new GeminiTextChatManager();

        // TTS (gemini-2.5-flash-preview-tts)
        ttsManager = new GeminiTTSManager();
    }

    private void setupClickListeners() {
        // Session toggle
        btnSession.setOnClickListener(v -> {
            animateButtonPress(v);
            if (sessionActive) {
                stopSession();
            } else {
                startSession();
            }
        });

        // Keyboard toggle
        findViewById(R.id.btnKeyboard).setOnClickListener(v -> {
            animateButtonPress(v);
            toggleTextInput();
        });

        // Mute toggle
        findViewById(R.id.btnMute).setOnClickListener(v -> {
            animateButtonPress(v);
            toggleMute();
        });

        // Clear chat
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            animateButtonPress(v);
            chatAdapter.clearMessages();
            textChatManager.clearHistory();
        });

        // Send text
        findViewById(R.id.btnSend).setOnClickListener(v -> sendTextMessage());

        // Text input IME action
        textInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextMessage();
                return true;
            }
            return false;
        });

        // API key / URL save
        findViewById(R.id.btnSaveApiKey).setOnClickListener(v -> {
            String url = apiUrlInput.getText().toString().trim();
            String key = apiKeyInput.getText().toString().trim();

            if (!TextUtils.isEmpty(url)) {
                // API URL mode - fetch key + personality from admin panel
                prefs.edit().putString(KEY_API_URL, url).apply();
                hideApiKeyOverlay();
                Toast.makeText(this, "Connecting to admin panel...", Toast.LENGTH_SHORT).show();
                fetchSettingsFromApi(url);
            } else if (!TextUtils.isEmpty(key)) {
                // Direct API key mode
                prefs.edit().putString(KEY_API_KEY, key).putString(KEY_API_URL, "").apply();
                hideApiKeyOverlay();
                Toast.makeText(this, "API Key saved!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Enter API URL or API Key!", Toast.LENGTH_SHORT).show();
            }
        });

        // Permission dialog buttons
        findViewById(R.id.btnPermissionClose).setOnClickListener(v -> {
            permissionOverlay.setVisibility(View.GONE);
        });

        findViewById(R.id.btnPermissionRefresh).setOnClickListener(v -> {
            permissionOverlay.setVisibility(View.GONE);
            requestMicPermission();
        });
    }

    private void setupChatRecyclerView() {
        chatAdapter = new ChatAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        chatRecyclerView.setLayoutManager(layoutManager);
        chatRecyclerView.setAdapter(chatAdapter);
    }

    // ========================
    // Permissions
    // ========================

    private void requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Mic permission already granted");
            return;
        }

        Log.d(TAG, "Requesting mic permission");
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
    }

    private boolean hasMicPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Mic permission granted");
                Toast.makeText(this, "Microphone enabled!", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "Mic permission denied");
                showPermissionDialog();
            }
        }
    }

    // ========================
    // API Settings Fetch
    // ========================

    private void fetchSettingsFromApi(String apiUrl) {
        new Thread(() -> {
            try {
                okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                okhttp3.Request request = new okhttp3.Request.Builder().url(apiUrl).build();
                okhttp3.Response response = httpClient.newCall(request).execute();
                String body = response.body().string();
                org.json.JSONObject json = new org.json.JSONObject(body);

                boolean keySet = false;
                if (json.has("api_key")) {
                    String key = json.getString("api_key");
                    if (!key.isEmpty() && !"YOUR_API_KEY_HERE".equals(key)) {
                        prefs.edit().putString(KEY_API_KEY, key).apply();
                        keySet = true;
                        Log.d(TAG, "API key fetched from admin panel");
                    }
                }
                if (json.has("personality")) {
                    String p = json.getString("personality");
                    if (!p.isEmpty()) {
                        prefs.edit().putString(KEY_PERSONALITY, p).apply();
                        webSocketManager.setPersonality(p);
                    }
                }
                if (!keySet) {
                    Log.w(TAG, "Admin panel has no valid API key set");
                    mainHandler.post(() -> {
                        chatAdapter.addMessage(new ChatMessage("API key not set in admin panel", ChatMessage.TYPE_ZOYA));
                        scrollToBottom();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch API settings: " + e.getMessage());
            }
        }).start();
    }

    // ========================
    // Session Management
    // ========================

    private void debugLog(String msg) {
        Log.d(TAG, msg);
    }

    private void startSession() {
        String apiKey = prefs.getString(KEY_API_KEY, "");

        if (TextUtils.isEmpty(apiKey) || "YOUR_API_KEY_HERE".equals(apiKey)) {
            chatAdapter.addMessage(new ChatMessage("API key not set in admin panel", ChatMessage.TYPE_ZOYA));
            scrollToBottom();
            return;
        }

        // Apply saved personality
        String personality = prefs.getString(KEY_PERSONALITY, "");
        if (!personality.isEmpty()) {
            webSocketManager.setPersonality(personality);
        }

        if (!hasMicPermission()) {
            Toast.makeText(this, "Microphone permission required!", Toast.LENGTH_SHORT).show();
            requestMicPermission();
            return;
        }

        try {
            sessionActive = true;
            btnSession.setText("END SESSION");
            btnSession.setBackgroundResource(R.drawable.bg_session_button_active);
            btnSession.setTextColor(ContextCompat.getColor(this, R.color.red_stop));

            updateState(AppState.PROCESSING);
            webSocketManager.connect(apiKey);
        } catch (Exception e) {
            Log.e(TAG, "Error starting session: " + e.getMessage(), e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopSession();
        }
    }

    private void stopSession() {
        sessionActive = false;
        pendingTextMessage = false;
        pendingText = null;

        try {
            webSocketManager.disconnect();
            audioManager.release();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping session: " + e.getMessage(), e);
        }

        btnSession.setText("START SESSION");
        btnSession.setBackgroundResource(R.drawable.bg_session_button);
        btnSession.setTextColor(ContextCompat.getColor(this, R.color.white));

        updateState(AppState.IDLE);
    }

    // ========================
    // State Management
    // ========================

    private void updateState(AppState newState) {
        currentState = newState;

        mainHandler.post(() -> {
            switch (newState) {
                case IDLE:
                    visualizer.setState(ZoyaVisualizerView.State.IDLE);
                    break;
                case LISTENING:
                    visualizer.setState(ZoyaVisualizerView.State.LISTENING);
                    break;
                case PROCESSING:
                    visualizer.setState(ZoyaVisualizerView.State.PROCESSING);
                    break;
                case SPEAKING:
                    visualizer.setState(ZoyaVisualizerView.State.SPEAKING);
                    break;
            }
            updateStatusIndicators(newState);
        });
    }

    private void updateStatusIndicators(AppState state) {
        if (state == AppState.LISTENING) {
            showViewAnimated(listeningIndicator);
            startDotPulse();
        } else {
            hideViewAnimated(listeningIndicator);
            stopDotPulse();
        }

        if (state == AppState.PROCESSING || state == AppState.SPEAKING) {
            showViewAnimated(replyingIndicator);
        } else {
            hideViewAnimated(replyingIndicator);
        }
    }

    private void startDotPulse() {
        if (dotPulseAnimator != null) dotPulseAnimator.cancel();
        dotPulseAnimator = ValueAnimator.ofFloat(0.3f, 1f);
        dotPulseAnimator.setDuration(800);
        dotPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        dotPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        dotPulseAnimator.addUpdateListener(a -> listeningDot.setAlpha((float) a.getAnimatedValue()));
        dotPulseAnimator.start();
    }

    private void stopDotPulse() {
        if (dotPulseAnimator != null) {
            dotPulseAnimator.cancel();
            dotPulseAnimator = null;
        }
    }

    // ========================
    // Text Input
    // ========================

    private void toggleTextInput() {
        textInputVisible = !textInputVisible;
        if (textInputVisible) {
            textInputBar.setVisibility(View.VISIBLE);
            textInputBar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in));
            textInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT);
        } else {
            textInputBar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_out));
            textInputBar.postDelayed(() -> textInputBar.setVisibility(View.GONE), 300);
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
        }
    }

    private void sendTextMessage() {
        String text = textInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        chatAdapter.addMessage(new ChatMessage(text, ChatMessage.TYPE_USER));
        scrollToBottom();
        textInput.setText("");

        // If voice session is active, send through WebSocket
        if (sessionActive) {
            if (!webSocketManager.isSetupComplete()) {
                pendingTextMessage = true;
                pendingText = text;
                Toast.makeText(this, "Connecting... message will be sent shortly", Toast.LENGTH_SHORT).show();
                return;
            }
            updateState(AppState.PROCESSING);
            webSocketManager.sendTextMessage(text);
            return;
        }

        // Voice session is NOT active → use text chat model (gemini-2.0-flash)
        String apiKey = prefs.getString(KEY_API_KEY, "");
        if (TextUtils.isEmpty(apiKey)) {
            chatAdapter.addMessage(new ChatMessage("API key not set in admin panel", ChatMessage.TYPE_ZOYA));
            scrollToBottom();
            return;
        }

        updateState(AppState.PROCESSING);
        Toast.makeText(this, "Zoya soch rahi hai...", Toast.LENGTH_SHORT).show();

        textChatManager.sendMessage(apiKey, text, new GeminiTextChatManager.TextChatCallback() {
            @Override
            public void onResponse(String responseText) {
                mainHandler.post(() -> {
                    chatAdapter.addMessage(new ChatMessage(responseText, ChatMessage.TYPE_ZOYA));
                    scrollToBottom();

                    // Now convert response to speech using TTS model
                    updateState(AppState.SPEAKING);
                    speakWithTTS(responseText);
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    chatAdapter.addMessage(new ChatMessage("Error: " + error, ChatMessage.TYPE_ZOYA));
                    scrollToBottom();
                    updateState(AppState.IDLE);
                    Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void speakWithTTS(String text) {
        String apiKey = prefs.getString(KEY_API_KEY, "");
        if (TextUtils.isEmpty(apiKey)) return;

        // Initialize audio for playback if not already
        try {
            audioManager.startPlayback();
        } catch (Exception e) {
            Log.e(TAG, "Error starting playback: " + e.getMessage());
        }

        ttsManager.synthesize(apiKey, text, new GeminiTTSManager.TTSCallback() {
            @Override
            public void onAudioReady(byte[] pcmData) {
                audioManager.enqueueAudio(pcmData);
                mainHandler.post(() -> updateState(AppState.IDLE));
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "TTS error: " + error);
                mainHandler.post(() -> {
                    updateState(AppState.IDLE);
                    Toast.makeText(MainActivity.this, "TTS: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ========================
    // GeminiListener Callbacks (Voice Session)
    // ========================

    @Override
    public void onConnected() {
        debugLog("WebSocket connected");
    }

    @Override
    public void onSetupComplete() {
        debugLog("Setup complete");
        mainHandler.post(() -> {
            Toast.makeText(this, "Zoya is ready! Start speaking...", Toast.LENGTH_SHORT).show();
            updateState(AppState.LISTENING);

            try {
                audioManager.startRecording();
            } catch (Exception e) {
                Log.e(TAG, "AudioRecord failed: " + e.getMessage(), e);
            }

            try {
                audioManager.startPlayback();
            } catch (Exception e) {
                Log.e(TAG, "AudioTrack failed: " + e.getMessage(), e);
            }

            // Send any pending text message
            if (pendingTextMessage && pendingText != null) {
                updateState(AppState.PROCESSING);
                webSocketManager.sendTextMessage(pendingText);
                pendingTextMessage = false;
                pendingText = null;
            }
        });
    }

    @Override
    public void onAudioData(byte[] audioData) {
        if (currentState != AppState.SPEAKING) {
            updateState(AppState.SPEAKING);
        }
        audioManager.enqueueAudio(audioData);
    }

    @Override
    public void onTextResponse(String text) {
        currentResponseText.append(text);
    }

    @Override
    public void onTurnComplete() {
        mainHandler.post(() -> {
            if (currentResponseText.length() > 0) {
                chatAdapter.addMessage(
                        new ChatMessage(currentResponseText.toString(), ChatMessage.TYPE_ZOYA));
                scrollToBottom();
                currentResponseText.setLength(0);
            }
            updateState(AppState.LISTENING);
        });
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "Error: " + error);
        mainHandler.post(() -> {
            String msg = error != null ? error : "Unknown error";
            chatAdapter.addMessage(new ChatMessage("Error: " + msg, ChatMessage.TYPE_ZOYA));
            scrollToBottom();
            Toast.makeText(this, "Error: " + msg, Toast.LENGTH_LONG).show();
            if (sessionActive) {
                stopSession();
            }
        });
    }

    @Override
    public void onDisconnected() {
        debugLog("WebSocket disconnected");
        mainHandler.post(() -> {
            if (sessionActive) {
                stopSession();
            }
        });
    }

    @Override
    public void onInterrupted() {
        mainHandler.post(() -> {
            audioManager.clearPlaybackQueue();
            updateState(AppState.LISTENING);
        });
    }

    @Override
    public void onDebug(String message) {
        debugLog(message);
    }

    // ========================
    // Overlays
    // ========================

    private void showPermissionDialog() {
        permissionOverlay.setVisibility(View.VISIBLE);
        permissionOverlay.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in));
    }

    private void showApiKeyOverlay() {
        apiKeyOverlay.setVisibility(View.VISIBLE);
        apiKeyOverlay.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in));
    }

    private void hideApiKeyOverlay() {
        apiKeyOverlay.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_out));
        apiKeyOverlay.postDelayed(() -> apiKeyOverlay.setVisibility(View.GONE), 300);
    }

    // ========================
    // Mute
    // ========================

    private void toggleMute() {
        boolean muted = !audioManager.isMuted();
        audioManager.setMuted(muted);
        muteIcon.setText(muted ? "🔇" : "🔊");
        Toast.makeText(this, muted ? "Muted" : "Unmuted", Toast.LENGTH_SHORT).show();
    }

    // ========================
    // UI Helpers
    // ========================

    private void scrollToBottom() {
        if (chatAdapter.getItemCount() > 0) {
            chatRecyclerView.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
        }
    }

    private void animateButtonPress(View v) {
        ObjectAnimator scaleDown = ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.9f);
        scaleDown.setDuration(100);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.9f);
        scaleDownY.setDuration(100);

        ObjectAnimator scaleUp = ObjectAnimator.ofFloat(v, "scaleX", 0.9f, 1f);
        scaleUp.setDuration(150);
        scaleUp.setStartDelay(100);
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(v, "scaleY", 0.9f, 1f);
        scaleUpY.setDuration(150);
        scaleUpY.setStartDelay(100);

        scaleDown.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleDownY.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleUp.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleUpY.setInterpolator(new AccelerateDecelerateInterpolator());

        scaleDown.start();
        scaleDownY.start();
        scaleUp.start();
        scaleUpY.start();
    }

    private void showViewAnimated(View view) {
        if (view.getVisibility() != View.VISIBLE) {
            view.setVisibility(View.VISIBLE);
            view.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in));
        }
    }

    private void hideViewAnimated(View view) {
        if (view.getVisibility() == View.VISIBLE) {
            view.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_out));
            view.postDelayed(() -> view.setVisibility(View.GONE), 300);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDotPulse();
        if (sessionActive) {
            stopSession();
        }
        audioManager.release();
    }
}
