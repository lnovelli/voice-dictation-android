# Voice Dictation for Android

An on-device, privacy-first voice dictation application for Android powered by NVIDIA NeMo Parakeet TDT 0.6B models and sherpa-onnx.

Voice Dictation brings seamless system-wide voice input to any text field across Android without sending audio data to third-party cloud servers.

---

## Features

- **Local & Private Inference:** Runs locally on your Android device using `sherpa-onnx` and ONNX Runtime Mobile with ARM NEON CPU acceleration.
- **State-of-the-Art ASR Models:**
  - **Parakeet TDT 0.6B v2 (English):** High accuracy, trained on 36,000 hours of speech, with native punctuation and capitalization.
  - **Parakeet TDT 0.6B v3 (Multilingual):** Supports 25 European languages with punctuation and capitalization.
- **One-Click Model Download:** In-app model downloader with real-time progress, background transfer, and automatic local archive extraction.
- **Smart Floating Overlay Button:**
  - Automatically appears when any editable text input field receives focus across the operating system.
  - Smoothly draggable to any position on screen with saved coordinate persistence.
  - Hides automatically when focus moves away from text fields.
- **Live Incremental Transcription:**
  - Integrated Silero Voice Activity Detector (VAD).
  - Processes speech incrementally as you speak.
  - Tap microphone icon to start recording; icon transforms into a stop button with a pulsing recording indicator.
  - Tap stop to finalize transcription and automatically inject text into the focused field (with automatic clipboard fallback).
- **Custom Vocabulary (Context Biasing):**
  - Add technical terms, names, or specialized acronyms in the dedicated Vocabulary tab.
  - Applies modified beam search with a configurable hotwords bonus score.
- **Transcription History:**
  - Automatically stores recent transcriptions with timestamps.
  - Configurable retention limit (default 10, adjustable from 5 to 50).
  - One-tap clipboard copy and deletion.
- **Self-Hosted In-App Updates:**
  - Integrated update checker comparing local build against self-hosted release manifests.
  - Seamless download and installation prompt via `FileProvider`.

---

## Download & Installation

Direct APK download:
[Download Voice Dictation APK](https://example.invalid/download/voice-dictation.apk)

Update Manifest:
`https://example.invalid/download/voice-dictation-version.json`

---

## Setup & Permissions

To enable system-wide dictation across apps:

1. **Accessibility Service:** Enable *Voice Dictation Accessibility Service* under Android Settings > Accessibility. This allows the app to detect focused text fields and insert transcribed text.
2. **Display Over Other Apps:** Grant overlay permission to allow the floating dictation button to appear on top of other applications.
3. **Microphone Permission:** Grant audio recording permission for on-device voice capture.
4. **Download Model:** Navigate to the **Settings** tab in the app and tap **Download Model** for either Parakeet v2 (English) or Parakeet v3 (Multilingual).

---

## Building from Source

### Prerequisites
- JDK 17 or JDK 21
- Android SDK (API Level 34, Build Tools 34.0.0+)
- Android NDK (ARM64-v8a / ARMEABI-v7a support)

### Build Commands
```bash
git clone https://github.com/lnovelli/voice-dictation-android.git
cd voice-dictation-android
./gradlew assembleDebug
```

The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## License

MIT License. Models and runtimes are subject to NVIDIA NeMo and Next-gen Kaldi / sherpa-onnx licenses.
