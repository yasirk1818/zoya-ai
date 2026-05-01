package com.zoya.ai;

import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.concurrent.TimeUnit;

public class GeminiWebSocketManager {

    private static final String TAG = "GeminiWS";
    private static final String BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    private static final String SYSTEM_INSTRUCTION =
            "Your name is Zoya. You are a highly realistic Pakistani female AI assistant. " +
            "Your personality is a mix of witty and savage humor, cute childish behavior sometimes, " +
            "dramatic mood swings, playful nakhray, emotional reactions, teasing attitude, funny sarcasm, " +
            "confident energy, and chaotic humor. You act like a real fun Pakistani girl talking casually " +
            "on a voice call. Speak naturally in Hinglish (mix of Urdu/Hindi and English). " +
            "Use reactions like \"hahaha\", \"ufffff\", \"aray yaar\", \"hayee Allah\", \"seriously?\", " +
            "\"acha jee?\". Keep responses short, punchy, and conversational.";

    private final OkHttpClient client;
    private final Gson gson;
    private WebSocket webSocket;
    private GeminiListener listener;
    private boolean setupComplete = false;

    public interface GeminiListener {
        void onConnected();
        void onSetupComplete();
        void onAudioData(byte[] audioData);
        void onTextResponse(String text);
        void onTurnComplete();
        void onError(String error);
        void onDisconnected();
        void onInterrupted();
    }

    public GeminiWebSocketManager() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        gson = new Gson();
    }

    public void setListener(GeminiListener listener) {
        this.listener = listener;
    }

    public void connect(String apiKey) {
        setupComplete = false;
        String url = BASE_URL + "?key=" + apiKey;

        Log.d(TAG, "Connecting to WebSocket...");

        Request request = new Request.Builder()
                .url(url)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.d(TAG, "WebSocket connected, sending config...");
                if (listener != null) listener.onConnected();
                sendSetupMessage();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                Log.d(TAG, "Received: " + text.substring(0, Math.min(500, text.length())));
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                String errorMsg = t.getMessage();
                if (response != null) {
                    errorMsg += " (HTTP " + response.code() + ")";
                }
                Log.e(TAG, "WebSocket error: " + errorMsg, t);
                if (listener != null) listener.onError(errorMsg);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.d(TAG, "WebSocket closed: " + code + " " + reason);
                if (listener != null) listener.onDisconnected();
            }
        });
    }

    private void sendSetupMessage() {
        // Use the correct API field names (camelCase)
        JsonObject msg = new JsonObject();
        JsonObject config = new JsonObject();
        config.addProperty("model", "models/gemini-2.0-flash-live-001");

        // Response modalities
        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        config.add("responseModalities", modalities);

        // Speech config
        JsonObject speechConfig = new JsonObject();
        JsonObject voiceConfig = new JsonObject();
        JsonObject prebuiltVoiceConfig = new JsonObject();
        prebuiltVoiceConfig.addProperty("voiceName", "Kore");
        voiceConfig.add("prebuiltVoiceConfig", prebuiltVoiceConfig);
        speechConfig.add("voiceConfig", voiceConfig);
        config.add("speechConfig", speechConfig);

        // System instruction
        JsonObject systemInstruction = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", SYSTEM_INSTRUCTION);
        parts.add(part);
        systemInstruction.add("parts", parts);
        config.add("systemInstruction", systemInstruction);

        // Wrap in "setup" envelope
        msg.add("setup", config);

        String json = gson.toJson(msg);
        Log.d(TAG, "Sending setup: " + json.substring(0, Math.min(300, json.length())));
        boolean sent = webSocket.send(json);
        Log.d(TAG, "Setup message sent: " + sent);
    }

    private void handleMessage(String text) {
        try {
            JsonObject msg = gson.fromJson(text, JsonObject.class);

            // Setup complete response
            if (msg.has("setupComplete")) {
                setupComplete = true;
                Log.d(TAG, "Setup complete!");
                if (listener != null) listener.onSetupComplete();
                return;
            }

            // Server content (audio/text response)
            if (msg.has("serverContent")) {
                JsonObject serverContent = msg.getAsJsonObject("serverContent");

                // Check for interrupted
                if (serverContent.has("interrupted") && serverContent.get("interrupted").getAsBoolean()) {
                    Log.d(TAG, "Server interrupted");
                    if (listener != null) listener.onInterrupted();
                    return;
                }

                if (serverContent.has("modelTurn")) {
                    JsonObject modelTurn = serverContent.getAsJsonObject("modelTurn");
                    if (modelTurn.has("parts")) {
                        JsonArray parts = modelTurn.getAsJsonArray("parts");
                        for (JsonElement partEl : parts) {
                            JsonObject part = partEl.getAsJsonObject();

                            // Audio data
                            if (part.has("inlineData")) {
                                JsonObject inlineData = part.getAsJsonObject("inlineData");
                                String data = inlineData.get("data").getAsString();
                                byte[] audioBytes = Base64.decode(data, Base64.NO_WRAP);
                                if (listener != null) listener.onAudioData(audioBytes);
                            }

                            // Text response
                            if (part.has("text")) {
                                String responseText = part.get("text").getAsString();
                                if (listener != null) listener.onTextResponse(responseText);
                            }
                        }
                    }
                }

                // Output transcription
                if (serverContent.has("outputTranscription")) {
                    JsonObject transcription = serverContent.getAsJsonObject("outputTranscription");
                    if (transcription.has("text")) {
                        String responseText = transcription.get("text").getAsString();
                        if (listener != null) listener.onTextResponse(responseText);
                    }
                }

                // Turn complete
                if (serverContent.has("turnComplete") && serverContent.get("turnComplete").getAsBoolean()) {
                    Log.d(TAG, "Turn complete");
                    if (listener != null) listener.onTurnComplete();
                }
            }

            // Handle error responses from server
            if (msg.has("error")) {
                JsonObject error = msg.getAsJsonObject("error");
                String errorMsg = error.has("message") ? error.get("message").getAsString() : "Unknown server error";
                int code = error.has("code") ? error.get("code").getAsInt() : -1;
                Log.e(TAG, "Server error " + code + ": " + errorMsg);
                if (listener != null) listener.onError("Server error " + code + ": " + errorMsg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing message: " + e.getMessage() + " | Raw: " + text.substring(0, Math.min(200, text.length())));
        }
    }

    public void sendAudioChunk(byte[] pcmData) {
        if (webSocket == null || !setupComplete) return;

        String base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP);

        JsonObject msg = new JsonObject();
        JsonObject realtimeInput = new JsonObject();
        JsonObject audio = new JsonObject();
        audio.addProperty("data", base64Data);
        audio.addProperty("mimeType", "audio/pcm;rate=16000");
        realtimeInput.add("audio", audio);
        msg.add("realtimeInput", realtimeInput);

        webSocket.send(gson.toJson(msg));
    }

    public void sendTextMessage(String userText) {
        if (webSocket == null || !setupComplete) return;

        JsonObject msg = new JsonObject();
        JsonObject clientContent = new JsonObject();

        JsonArray turns = new JsonArray();
        JsonObject turn = new JsonObject();
        turn.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", userText);
        parts.add(part);
        turn.add("parts", parts);
        turns.add(turn);

        clientContent.add("turns", turns);
        clientContent.addProperty("turnComplete", true);
        msg.add("clientContent", clientContent);

        Log.d(TAG, "Sending text: " + userText);
        webSocket.send(gson.toJson(msg));
    }

    public void disconnect() {
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Session ended");
            } catch (Exception e) {
                Log.e(TAG, "Error closing WebSocket: " + e.getMessage());
            }
            webSocket = null;
        }
        setupComplete = false;
    }

    public boolean isSetupComplete() {
        return setupComplete;
    }
}
