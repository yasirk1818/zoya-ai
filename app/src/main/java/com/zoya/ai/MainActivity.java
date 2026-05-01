package com.zoya.ai;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private static final String DEFAULT_API_KEY = "AIzaSyB8F9JruBh5O-nBnWjgGsuccTPLNtyHn8A";

    // State
    private enum AppState { IDLE, LISTENING, PROCESSING, SPEAKING }
    private AppState currentState = AppState.IDLE;
    private boolean sessionActive = false;
    private boolean textInputVisible = false;

    // Managers
    private GeminiWebSocketManager webSocketManager;
    private ZoyaAudioManager audioManager;

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

        // Set default API key if none saved
        String savedKey = prefs.getString(KEY_API_KEY, "");
        if (TextUtils.isEmpty(savedKey)) {
            prefs.edit().putString(KEY_API_KEY, DEFAULT_API_KEY).apply();
        }
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
        permissionOverlay = findViewById(R.id.permissionOverlay);
        listeningIndicator = findViewById(R.id.listeningIndicator);
        replyingIndicator = findViewById(R.id.replyingIndicator);
        listeningDot = findViewById(R.id.listeningDot);
    }

    private void initManagers() {
        webSocketManager = new GeminiWebSocketManager();
        webSocketManager.setListener(this);

        audioManager = new ZoyaAudioManager();
        audioManager.setAudioCallback(data -> {
            if (sessionActive) {
                webSocketManager.sendAudioChunk(data);
            }
        });
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

        // API key save
        findViewById(R.id.btnSaveApiKey).setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            if (!TextUtils.isEmpty(key)) {
                prefs.edit().putString(KEY_API_KEY, key).apply();
                hideApiKeyOverlay();
            }
        });

        // Permission dialog buttons
        findViewById(R.id.btnPermissionClose).setOnClickListener(v -> {
            permissionOverlay.setVisibility(View.GONE);
        });

        findViewById(R.id.btnPermissionRefresh).setOnClickListener(v -> {
            permissionOverlay.setVisibility(View.GONE);
            checkAndRequestPermission();
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
    // Session Management
    // ========================

    private void startSession() {
        String apiKey = prefs.getString(KEY_API_KEY, "");
        if (TextUtils.isEmpty(apiKey)) {
            showApiKeyOverlay();
            return;
        }

        if (!checkAndRequestPermission()) {
            return;
        }

        sessionActive = true;
        btnSession.setText("END SESSION");
        btnSession.setBackgroundResource(R.drawable.bg_session_button_active);
        btnSession.setTextColor(ContextCompat.getColor(this, R.color.red_stop));

        updateState(AppState.PROCESSING);
        webSocketManager.connect(apiKey);
    }

    private void stopSession() {
        sessionActive = false;
        webSocketManager.disconnect();
        audioManager.release();

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
            // Update visualizer
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

            // Update status indicators
            updateStatusIndicators(newState);
        });
    }

    private void updateStatusIndicators(AppState state) {
        // Listening indicator
        if (state == AppState.LISTENING) {
            showViewAnimated(listeningIndicator);
            startDotPulse();
        } else {
            hideViewAnimated(listeningIndicator);
            stopDotPulse();
        }

        // Replying indicator
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

        if (!sessionActive) {
            startSession();
        }

        chatAdapter.addMessage(new ChatMessage(text, ChatMessage.TYPE_USER));
        scrollToBottom();
        textInput.setText("");

        updateState(AppState.PROCESSING);
        webSocketManager.sendTextMessage(text);
    }

    // ========================
    // GeminiListener Callbacks
    // ========================

    @Override
    public void onConnected() {
        // Wait for setup complete
    }

    @Override
    public void onSetupComplete() {
        mainHandler.post(() -> {
            updateState(AppState.LISTENING);
            audioManager.startRecording();
            audioManager.startPlayback();
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
        mainHandler.post(() -> {
            chatAdapter.addMessage(new ChatMessage("Connection error: " + error, ChatMessage.TYPE_ZOYA));
            scrollToBottom();
            if (sessionActive) {
                stopSession();
            }
        });
    }

    @Override
    public void onDisconnected() {
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

    // ========================
    // Permissions
    // ========================

    private boolean checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            showPermissionDialog();
            return false;
        }

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSession();
            } else {
                showPermissionDialog();
            }
        }
    }

    private void showPermissionDialog() {
        permissionOverlay.setVisibility(View.VISIBLE);
        permissionOverlay.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in));
    }

    // ========================
    // API Key Overlay
    // ========================

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
