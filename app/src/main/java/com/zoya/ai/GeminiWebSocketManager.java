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

        Request request = new Request.Builder()
                .url(url)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.d(TAG, "WebSocket connected");
                if (listener != null) listener.onConnected();
                sendSetupMessage();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket error: " + t.getMessage());
                if (listener != null) listener.onError(t.getMessage());
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.d(TAG, "WebSocket closed: " + reason);
                if (listener != null) listener.onDisconnected();
            }
        });
    }

    private void sendSetupMessage() {
        JsonObject setup = new JsonObject();
        JsonObject setupData = new JsonObject();
        setupData.addProperty("model", "models/gemini-2.0-flash-live-001");

        // Generation config
        JsonObject genConfig = new JsonObject();
        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        genConfig.add("response_modalities", modalities);

        JsonObject speechConfig = new JsonObject();
        JsonObject voiceConfig = new JsonObject();
        JsonObject prebuiltVoice = new JsonObject();
        prebuiltVoice.addProperty("voice_name", "Kore");
        voiceConfig.add("prebuilt_voice_config", prebuiltVoice);
        speechConfig.add("voice_config", voiceConfig);
        genConfig.add("speech_config", speechConfig);

        setupData.add("generation_config", genConfig);

        // System instruction
        JsonObject sysInstruction = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", SYSTEM_INSTRUCTION);
        parts.add(part);
        sysInstruction.add("parts", parts);
        setupData.add("system_instruction", sysInstruction);

        setup.add("setup", setupData);

        String json = gson.toJson(setup);
        Log.d(TAG, "Sending setup: " + json.substring(0, Math.min(200, json.length())));
        webSocket.send(json);
    }

    private void handleMessage(String text) {
        try {
            JsonObject msg = gson.fromJson(text, JsonObject.class);

            // Setup complete response
            if (msg.has("setupComplete")) {
                setupComplete = true;
                Log.d(TAG, "Setup complete");
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

                // Turn complete
                if (serverContent.has("turnComplete") && serverContent.get("turnComplete").getAsBoolean()) {
                    if (listener != null) listener.onTurnComplete();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing message: " + e.getMessage());
        }
    }

    public void sendAudioChunk(byte[] pcmData) {
        if (webSocket == null || !setupComplete) return;

        String base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP);

        JsonObject msg = new JsonObject();
        JsonObject realtimeInput = new JsonObject();
        JsonArray mediaChunks = new JsonArray();
        JsonObject chunk = new JsonObject();
        chunk.addProperty("mimeType", "audio/pcm;rate=16000");
        chunk.addProperty("data", base64Data);
        mediaChunks.add(chunk);
        realtimeInput.add("mediaChunks", mediaChunks);
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

        webSocket.send(gson.toJson(msg));
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "Session ended");
            webSocket = null;
        }
        setupComplete = false;
    }

    public boolean isSetupComplete() {
        return setupComplete;
    }
}
