package com.zoya.ai;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GeminiTextChatManager {

    private static final String TAG = "GeminiTextChat";
    private static final String MODEL = "gemini-2.5-flash";
    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final String SYSTEM_INSTRUCTION =
            "Your name is Zoya. You are a highly realistic Pakistani female AI assistant. " +
            "Your personality is a mix of witty and savage humor, cute childish behavior sometimes, " +
            "dramatic mood swings, playful nakhray, emotional reactions, teasing attitude, funny sarcasm, " +
            "confident energy, and chaotic humor. You act like a real fun Pakistani girl talking casually. " +
            "Speak naturally in Hinglish (mix of Urdu/Hindi and English). " +
            "Use reactions like \"hahaha\", \"ufffff\", \"aray yaar\", \"hayee Allah\", \"seriously?\", " +
            "\"acha jee?\". Keep responses short, punchy, and conversational.";

    private final OkHttpClient client;
    private final Gson gson;
    private JsonArray conversationHistory;

    public interface TextChatCallback {
        void onResponse(String text);
        void onError(String error);
    }

    public GeminiTextChatManager() {
        client = new OkHttpClient();
        gson = new Gson();
        conversationHistory = new JsonArray();
    }

    public void sendMessage(String apiKey, String userMessage, TextChatCallback callback) {
        // Add user message to history
        JsonObject userTurn = new JsonObject();
        userTurn.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", userMessage);
        userParts.add(userPart);
        userTurn.add("parts", userParts);
        conversationHistory.add(userTurn);

        // Build request body
        JsonObject body = new JsonObject();
        body.add("contents", conversationHistory.deepCopy());

        // System instruction
        JsonObject sysInstruction = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysPart = new JsonObject();
        sysPart.addProperty("text", SYSTEM_INSTRUCTION);
        sysParts.add(sysPart);
        sysInstruction.add("parts", sysParts);
        body.add("systemInstruction", sysInstruction);

        String url = BASE_URL + "?key=" + apiKey;

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(gson.toJson(body), JSON_TYPE))
                .build();

        Log.d(TAG, "Sending text chat request...");

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed: " + e.getMessage());
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    JsonObject json = gson.fromJson(responseBody, JsonObject.class);

                    if (json.has("error")) {
                        JsonObject error = json.getAsJsonObject("error");
                        String errorMsg = error.has("message") ? error.get("message").getAsString() : "Unknown error";
                        Log.e(TAG, "API error: " + errorMsg);
                        callback.onError(errorMsg);
                        return;
                    }

                    StringBuilder textResponse = new StringBuilder();
                    JsonArray candidates = json.getAsJsonArray("candidates");
                    if (candidates != null && candidates.size() > 0) {
                        JsonObject content = candidates.get(0).getAsJsonObject()
                                .getAsJsonObject("content");
                        JsonArray parts = content.getAsJsonArray("parts");
                        for (int i = 0; i < parts.size(); i++) {
                            JsonObject part = parts.get(i).getAsJsonObject();
                            if (part.has("text")) {
                                textResponse.append(part.get("text").getAsString());
                            }
                        }

                        // Add assistant response to history
                        conversationHistory.add(content);
                    }

                    String result = textResponse.toString();
                    Log.d(TAG, "Got response: " + result.substring(0, Math.min(100, result.length())));
                    callback.onResponse(result);

                } catch (Exception e) {
                    Log.e(TAG, "Parse error: " + e.getMessage());
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }

    public void clearHistory() {
        conversationHistory = new JsonArray();
    }
}
