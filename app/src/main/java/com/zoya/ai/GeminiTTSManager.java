package com.zoya.ai;

import android.util.Base64;
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

public class GeminiTTSManager {

    private static final String TAG = "GeminiTTS";
    private static final String MODEL = "gemini-2.5-flash-preview-tts";
    private static final String BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final Gson gson;

    public interface TTSCallback {
        void onAudioReady(byte[] pcmData);
        void onError(String error);
    }

    public GeminiTTSManager() {
        client = new OkHttpClient();
        gson = new Gson();
    }

    public void synthesize(String apiKey, String text, TTSCallback callback) {
        JsonObject body = new JsonObject();

        // Contents
        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        parts.add(part);
        content.add("parts", parts);
        contents.add(content);
        body.add("contents", contents);

        // Generation config for audio output
        JsonObject genConfig = new JsonObject();
        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        genConfig.add("responseModalities", modalities);

        JsonObject speechConfig = new JsonObject();
        JsonObject voiceConfig = new JsonObject();
        JsonObject prebuiltVoiceConfig = new JsonObject();
        prebuiltVoiceConfig.addProperty("voiceName", "Kore");
        voiceConfig.add("prebuiltVoiceConfig", prebuiltVoiceConfig);
        speechConfig.add("voiceConfig", voiceConfig);
        genConfig.add("speechConfig", speechConfig);

        body.add("generationConfig", genConfig);

        String url = BASE_URL + "?key=" + apiKey;

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(gson.toJson(body), JSON_TYPE))
                .build();

        Log.d(TAG, "Requesting TTS for: " + text.substring(0, Math.min(50, text.length())));

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "TTS request failed: " + e.getMessage());
                callback.onError("TTS error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    JsonObject json = gson.fromJson(responseBody, JsonObject.class);

                    if (json.has("error")) {
                        JsonObject error = json.getAsJsonObject("error");
                        String errorMsg = error.has("message") ? error.get("message").getAsString() : "Unknown error";
                        Log.e(TAG, "TTS API error: " + errorMsg);
                        callback.onError(errorMsg);
                        return;
                    }

                    JsonArray candidates = json.getAsJsonArray("candidates");
                    if (candidates != null && candidates.size() > 0) {
                        JsonObject contentObj = candidates.get(0).getAsJsonObject()
                                .getAsJsonObject("content");
                        JsonArray parts = contentObj.getAsJsonArray("parts");
                        for (int i = 0; i < parts.size(); i++) {
                            JsonObject partObj = parts.get(i).getAsJsonObject();
                            if (partObj.has("inlineData")) {
                                JsonObject inlineData = partObj.getAsJsonObject("inlineData");
                                String data = inlineData.get("data").getAsString();
                                byte[] audioBytes = Base64.decode(data, Base64.NO_WRAP);
                                Log.d(TAG, "Got TTS audio: " + audioBytes.length + " bytes");
                                callback.onAudioReady(audioBytes);
                                return;
                            }
                        }
                    }

                    callback.onError("No audio data in TTS response");

                } catch (Exception e) {
                    Log.e(TAG, "TTS parse error: " + e.getMessage());
                    callback.onError("TTS parse error: " + e.getMessage());
                }
            }
        });
    }
}
