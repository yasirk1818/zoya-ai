# Zoya AI — Real-Time AI Voice Assistant

A cinematic Android voice assistant powered by **Google Gemini Live API**. Zoya is a Pakistani AI personality that speaks naturally in Hinglish with a witty, playful, and expressive character.

## Features

- **Real-time Voice Chat** — Continuous microphone input (PCM 16kHz) with live audio playback (PCM 24kHz) via WebSocket
- **Gemini Live API** — Bidirectional streaming through `BidiGenerateContent` WebSocket endpoint
- **Text Input Mode** — Fallback text messaging with chat bubble UI
- **Cinematic UI** — Dark theme with animated concentric ring visualizer, gradient bokeh blobs, and state-driven color transitions
- **Animated Visualizer** — 5 concentric rotating rings with per-state color and speed changes (idle/listening/processing/speaking)
- **Custom Typography** — Rajdhani font family throughout

## Architecture

| Component | Description |
|-----------|-------------|
| `MainActivity` | Orchestrates UI, state management, permissions, and session lifecycle |
| `ZoyaVisualizerView` | Custom `View` with Canvas-drawn animated rings, glow effects, and pulse animations |
| `GeminiWebSocketManager` | OkHttp WebSocket client for Gemini Live API (setup, audio chunks, text messages) |
| `ZoyaAudioManager` | `AudioRecord` + `AudioTrack` manager for real-time PCM audio I/O |
| `ChatAdapter` | RecyclerView adapter for animated chat message bubbles |

## Setup

1. Clone the repo
2. Open in Android Studio
3. Build and run on a device/emulator (min SDK 26)
4. On first launch, enter your [Google Gemini API key](https://aistudio.google.com/app/apikey)
5. Grant microphone permission
6. Tap "Start Session" to begin talking to Zoya

## Tech Stack

- **Language:** Java
- **Min SDK:** 26, Target SDK: 34
- **Dependencies:** OkHttp 4.12, Gson 2.10.1, AndroidX AppCompat, Material Components
- **No third-party UI libraries** — pure Android Canvas, ValueAnimator, ObjectAnimator

## WebSocket Protocol

The app connects to:
```
wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent
```

Messages follow the Gemini Live API format:
- **Setup** → model config, voice selection (Kore), system instruction
- **Audio input** → base64-encoded PCM chunks at 16kHz
- **Text input** → `clientContent` with user turns
- **Response** → audio data (24kHz PCM) and/or text parts

## Building

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## License

MIT
