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
  - Draggable partly beyond any screen edge, with saved position and a small reachable portion retained.
  - Adjustable floating icon size (50–150%) and opacity, applied immediately from Settings.
  - Hides automatically when focus moves away from text fields.
- **Accuracy-First Live Dictation:**
  - Silero VAD gates advisory previews; previews show up to the most recent 12 seconds and may change or omit earlier words.
  - Tap microphone to record; tap stop to run one authoritative decode of the entire captured recording, including pauses and short final reads. VAD segments/previews are never stitched into the final transcript.
  - Recording automatically stops and finalizes at 120 seconds. Final inference can take substantially longer than previews on slower phones.
  - Only the final transcript is inserted into the active focused field (with clipboard fallback); external IPC transcripts are never pasted or copied by the overlay.
  - Full context is intended to avoid segmentation-related loss, not guarantee correct recognition. Noise, accent, model/language choice, hotword bias, long silence and device constraints can still cause errors; review important text.
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

## Accuracy-first live dictation in v1.0.6 (build 7)

- Retains exact captured PCM independently of VAD, bounded to 120 seconds / 3,840,000 PCM bytes. One full-recording final decode replaces the old stitched-segment finalization; there is no partial-result fallback on final failure.
- Throttles rolling previews to at least two seconds between decodes, skips stale preview work, and disables previews after a recoverable preview failure without discarding recorded audio.
- Keeps native initialization, preview/final decode, stream cleanup and model destruction serialized. Cancellation stops the microphone promptly but must wait for an already-running native decode to return before ownership can be reused.
- Accessibility focus loss caused by notification/quick-settings panels no longer cancels an active floating session. Idle hiding, explicit stop/cancel, screen-off/lock safety, service interruption/destruction, disable, and IPC ownership checks remain in place.
- Does not insert into an old field covered by SystemUI; ordinary floating results fall back to history/clipboard when unlocked. Nothing is pasted or copied on screen lock or through IPC.
- Extends the protected IPC total session deadline to ten minutes for loading/capture/full-context processing. Clients should allow up to five minutes after STOP for final processing (device-dependent, not a latency guarantee), still canceling on genuine backgrounding/death.

See [Live dictation implementation review](docs/live-dictation-review.md) for buffer/native ownership details and verification limits.

## Hermes local dictation integration in v1.0.5 (build 6)

Voice Dictation exposes the signature-protected Messenger protocol described in
[External dictation IPC](docs/external-dictation.md). Hermes can use local ASR without
accessibility insertion or the floating overlay. In addition to live phone dictation,
Hermes can forward completed watch audio through `CMD_TRANSCRIBE_FILE = 4`: a
readable file descriptor containing raw 16 kHz mono PCM16LE, up to 120 seconds.
File transcription needs no phone microphone permission, uses a full-context final
decode, and shares secure exclusive ownership/cancellation with live dictation.
Ordinary floating dictation remains independent; v1.0.6 also uses a full-recording final decode for live capture.

## Overlay customization in v1.0.3

- Allows partial off-screen dragging on all four edges, including restoring saved negative coordinates.
- Adds a persistent 50–150% floating icon size slider in Settings, scaling the button, microphone/stop icon, processing spinner and recording ring together.
- Keeps a small part of the button reachable after dragging, resizing or rotating the display.

## Fix in v1.0.2

Dictation insertion refreshes the accessibility node and excludes displayed hint/placeholder text while preserving actual existing input.

## Reliability changes in v1.0.1

- Fixes the native process exit when custom vocabulary is enabled by configuring BPE tokenization and bundling matching Parakeet v2/v3 vocabularies. Vocabulary capitalization is preserved.
- Loads and releases native models off the UI thread, with decoding and destruction serialized to prevent use-after-release races.
- Separates microphone capture from inference using a bounded queue; reports overload instead of silently losing audio.
- Originally finalized VAD segments once; v1.0.6 supersedes this with full-recording finalization.
- Adds a persistent **Enable dictation service** switch at the top of Settings. Disabling cancels dictation, hides the overlay, and releases models without revoking Android accessibility permission.
- Includes regression tests for bounded audio buffering and tokenizer validation.

Device-specific microphone, overlay, and native inference behavior still requires verification on a physical Android phone.

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
