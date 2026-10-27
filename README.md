# Voice Dictation for Android

An on-device, privacy-first voice dictation application for Android powered by NVIDIA NeMo Parakeet TDT and Parakeet Unified models through sherpa-onnx.

Voice Dictation brings seamless system-wide voice input to any text field across Android without sending audio data to third-party cloud servers.

---

## Current release: v1.2.10 (build 31; ARM64-only)

Adds below-threshold AI polishing for whole-word “one” when enabled, so the model can decide from context; static number conversion leaves standalone “one” unchanged. Local dictation now copies results to the clipboard rather than inserting into a new field if focus changes.

See [live TDT chunking, safeguards, and limitations](docs/live-tdt-vad-chunking.md).

## Features

- **Local & Private Inference:** Runs locally on your Android device using `sherpa-onnx` and ONNX Runtime Mobile with ARM NEON CPU acceleration.
- **State-of-the-Art ASR Models:**
  - **Parakeet TDT 0.6B v2 (English):** High accuracy, trained on 36,000 hours of speech, with native punctuation and capitalization. Live microphone audio is decoded in ordered VAD-delimited chunks; completed files and History reprocessing keep their existing full-recording path.
  - **Parakeet TDT 0.6B v3 (Multilingual):** Supports 25 European languages with punctuation and capitalization. Live microphone audio is decoded in ordered VAD-delimited chunks; completed files and History reprocessing keep their existing full-recording path.
  - **Parakeet Unified 0.6B (English, Streaming):** sherpa-onnx online RNNT recognition processes audio during recording to reduce finalization work. The app does not display partial transcripts; the final result is returned on Stop. English only; the 560 ms buffered model preset is not a device-latency guarantee.
- **One-Click Model Download:** In-app model downloader with real-time progress, background transfer, and automatic local archive extraction for all three models.
- **Smart Floating Overlay Button:**
  - Automatically appears when any editable text input field receives focus across the operating system.
  - On Android 13+, system input-start/finish callbacks also detect editors that hide their accessibility nodes; the app does not read typed text or cursor positions.
  - Optional **Keep button visible while unlocked** mode supports apps that do not expose editable focus to accessibility services. It can overlap sensitive screens; Android can still hide overlays when an app explicitly blocks them, and inaccessible fields may require clipboard paste.
  - Draggable partly beyond any screen edge, with independent saved positions for portrait and landscape; an unset position starts vertically centered at the right edge.
  - Adjustable floating icon size (50–150%) and opacity, applied immediately from Settings.
  - In normal focus-triggered mode, hides when focus moves away from text fields; the optional always-visible mode stays on while the device is unlocked.
  - Settings can disable autofill/competing-window collision suppression. Dictation inserts only into the field focused when recording started; if focus changes or no field was focused, the result is copied to the clipboard with a brief toast.
- **Speech-activity indicator and live TDT chunking (1.2.4):** Bundles the 212,860-byte Silero 16 kHz INT8 model. During live microphone recording, the floating button keeps its breathing animation but is gray when the VAD reports no speech and red when it detects speech. TDT uses VAD endpoints to queue exact PCM ranges without trimming or filtering the original audio. If VAD is unavailable, recording continues and the remaining TDT audio is decoded unsplit.
- **Model-specific capture and recognition (1.2.1):**
  - **TDT v2/v3:** A separate Silero worker accumulates at least 5 seconds of VAD-classified speech across short pauses before releasing a chunk at a later pause. One serialized offline TDT worker decodes chunks in order. Stop drains the open tail and backlog, assembles text in range order, then runs the single final text-polishing step. All captured PCM remains authoritative; queues retain the full 10-minute capture if decoding falls behind. The optional acoustic vocabulary setting applies only to TDT.
  - **Parakeet Unified:** The online recognizer consumes bounded audio batches while AudioRecord continues capturing; Silero VAD separately supplies only the visual speech indicator. Partial text stays internal and is never shown or inserted. Explicit Stop drains queued audio, flushes the online stream and returns that final transcript without a second full-recording pass. A bounded queue can retain the whole 10-minute input if inference falls behind; in that case Stop still waits for the remaining backlog. Device throughput and latency need physical-phone validation.
  - All live modes retain the original PCM independently of recognition for the existing final transcript/history workflow. The Unified model is English-only; acoustic hotword bias is disabled for it, while final spelling/replacement/filler rules and optional text-only polishing remain unchanged.
  - Hold the recording button during capture or pending final processing to discard and suppress the result. An already-running native decode cannot be interrupted, but its output is discarded. Release after a hold cannot restart; dragging cancels the hold timer, and cancelled touch gestures do not stop or discard audio. Accessible click/long-click and haptic cues are provided.
  - At 10 minutes, microphone capture closes while bounded original PCM remains retained; TDT chunks may already have been decoded, but final assembly, polishing, and delivery wait for explicit Stop. A toast and brief sound/haptic cue indicate the limit; Stop stays available. Reaching the cap never initiates finalization.
  - Final transcripts go only into the editor focused at recording start; if that field is no longer active, or no editor was focused, the transcript is copied to the clipboard. External IPC transcripts are never pasted or copied by the overlay.
  - TDT inference can still be slow and memory intensive, especially for long uninterrupted chunks or full-recording fallback. Accuracy is **not guaranteed**: with unbiased greedy decoding, native v3 probes recognized short English/French clips but returned empty text when the same speech preceded silence to 120 seconds. With the hotword-free greedy configuration, silence returned no repeated vocabulary tokens. Review important text; see [verification and limitations](docs/record-then-transcribe-1.0.8.md).
- **Custom Vocabulary:**
  - Add technical terms, names or acronyms in the Vocabulary tab. The separate spelling switch can only recase or join/split spaces when the recognized letters already match.
  - Optional experimental acoustic hotword bias is also in the Vocabulary tab. It uses modified beam search with model-matched BPE vocab files; initial settings are four active paths, score 1.0 and fixed blank penalty 0.0. Its toggle is **off by default**; active paths and score have sliders.
  - Acoustic bias is not accuracy-safe: the pinned native decoder can reward completed terms on multiple paths, causing repetitions or changing/omitting unrelated speech. Keep disabled unless testing, and review all output; see [current controls and limitations](docs/acoustic-vocabulary-1.1.1.md) and [prior native probe details](docs/acoustic-vocabulary-1.1.0.md).
- **Custom String Replacements:**
  - In the Vocabulary tab, map multiple literal transcription variants to one output phrase. Matching is case-insensitive and respects whole-word/phrase boundaries; replacements are applied automatically to final transcripts and remain independent of acoustic word boosting.
- **Transcript formatting and polishing gates:**
  - Vocabulary settings can convert clear English number phrases to digits (off by default), set the AI-polishing minimum to 0–20 words (default 10), and optionally polish short transcripts containing nearby number sequences or the whole word “one”. Standalone “one” is left spelled out by static conversion; compound numerals such as “twenty one” still convert.
  - The word-count threshold (0–20, default 10) is evaluated before number-word conversion. When auto-polishing is enabled, transcripts below the threshold trigger polishing for nearby-number sequences such as “4 30 pm” or “16 oh five”, and for “one” outside the idiom “one another”; the model uses context to decide whether “one” should become `1`. The master AI-polishing switch must also be enabled.
- **Optional AI Transcript Polishing:**
  - The Polish tab configures and tests an OpenAI-compatible chat-completions endpoint and model. Defaults are `http://127.0.0.1:11434` and `default`; the master switch is off until explicitly enabled.
  - Sends your saved system prompt, final transcript and custom vocabulary as text, never audio. HTTP is restricted to `127.0.0.1`/`localhost`; external hosts require HTTPS. A bounded timeout, response-size limit, and no-redirect policy apply.
  - The built-in prompt preserves the speaker’s meaning, language and tone while lightly fixing grammar, syntax and clear recognition errors. It removes clear self-corrections, false starts and immediate repetitions, formats spoken numeric values and unambiguous times as digits, and avoids guessing or rewriting for style. The transcript and custom vocabulary are sent together as structured text; vocabulary is used only as a spelling/recognition hint. The configured local filler-removal and replacement rules still run afterward. The Polish tab lets you edit and save the system prompt locally, or restore the built-in prompt; that saved prompt is sent to the configured endpoint with each polishing request.
  - On connection, HTTP, timeout, malformed-response or empty-output failures, the original ASR transcript proceeds through the existing local post-processing.
- **Editable Final-Transcript Removal List:**
  - Enabled by default with conservative fillers `um`, `uhm`, `uh` (not ambiguous `ah`, `like` or `so`). Settings provides a switch, one-word/phrase-per-line editor, Save and Restore Defaults.
  - Removes literal case-insensitive Unicode whole words/phrases only, never substrings such as `um` inside `album`. Apostrophe/hyphen words and combining marks are protected; punctuation and spacing left by removals are cleaned conservatively.
  - Applies only after the single final decode, identically to floating/live/file results and history. Disable or save an empty list to preserve text. If everything is removed, normal no-speech ERROR safety applies; no empty result is submitted.
- **Transcription History:**
  - Automatically stores recent transcripts with timestamps and saves the complete original recording as a WAV alongside each new successful entry. The History tab can play saved audio or reprocess it to replace the existing transcript while keeping its ID, timestamp and recording.
  - The reprocess action uses the selected model and current text-processing settings, disables experimental acoustic hotword bias, and uses a fresh, non-overlapping low-energy segmentation strategy for long audio; all input samples are retained exactly once. It does not paste or submit the result. If AI polishing is enabled, the app asks before sending transcript text (never audio) to the configured server.
  - Audio stays in app-private storage excluded from Android backups; deleting, clearing, or trimming a history entry removes its audio. Live recordings are bounded to 10 minutes; completed-file IPC imports remain capped at 120 seconds. History is subject to the configured retention limit. Entries created before v1.1.7 have no audio attachment.
  - Configurable retention limit (default 10, adjustable from 5 to 50).
  - One-tap clipboard copy and deletion.
- **GitHub Releases In-App Updates:**
  - The manual update checker reads the latest stable release and its version manifest from the public GitHub repository.
  - Before offering installation, the app verifies the APK SHA-256, package name, version code, and signing identity.

---

## Record-then-transcribe in v1.0.8 (build 9)

- Removes all preview/VAD execution and the VAD download/readiness dependency. Existing ASR installations stay usable; legacy VAD files are harmless and ignored. Readiness requires the selected encoder, decoder, joiner and tokens only.
- Live model initialization is deferred until explicit Stop; completed command-4 files process directly without waiting or opening the phone microphone.
- Keeps cancellation/native mutex ownership, shade focus handling, screen-lock safety, scaled/off-screen drag bounds and the ten-minute protected IPC deadline. Cancellation during recording or cap-wait performs zero decoding; an already-running native call cannot be interrupted and its output is discarded.
- Protocol 1 and event IDs are unchanged. PARTIAL 102 is reserved and **never emitted**. New STATE `recording_limit` means capture ended, retain recording UI and await Stop/Cancel. `loading` can occur after Stop; only RESULT is authoritative.

## ASR regression fix in v1.0.7 (build 8; historical, windowing/previews superseded by 1.0.8)

- Removes vocabulary-triggered beam search from previews and final transcription. Both use the supported NeMo/TDT greedy path, with no hotword file, no token bonus and no blank penalty.
- Uses fresh final streams of at most 20 seconds, preferring quiet boundaries between 15 and 20 seconds. Preserves all original PCM without VAD filtering, overlap-text deduplication or repetition heuristics.
- Keeps existing vocabulary entries, now explicitly optional **spelling-only** hints. Acoustic bias and its unsafe score slider are disabled, not advertised as working.
- Reproduced the old regression using Linux sherpa-onnx **1.13.8**, official public models/audio and digital silence: v2 with hotword `Nextcloud`, score 2.0 generated 421 tokens on 12 seconds of silence; unbiased greedy returned empty text. Both greedy and beam retain TDT durations; the fault is not an RNNT fallback.
- Build/lint and native speech/silence exercises passed; no Android device was connected. See [source-backed diagnosis, transcripts and limitations](docs/asr-regression-1.0.7.md).

## Accuracy-first live dictation in v1.0.6 (build 7; historical, superseded by 1.0.8)

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
File transcription needs no phone microphone permission and shares secure exclusive
ownership/cancellation with live dictation. Since v1.0.8 both paths use exactly one
full-recording final decode; since v1.1.0 the opt-in experimental Vocabulary-tab
hotwords also apply to file dictation, while unbiased greedy remains the default.
Ordinary floating dictation remains independent.

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

Latest release and release notes:
https://github.com/lnovelli/voice-dictation-android/releases/latest

Each release includes `voice-dictation.apk` and `voice-dictation-version.json` with the version code/name, version-specific APK URL, SHA-256, and release notes. The updater discovers the current release through the GitHub Releases API.

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
- Packaged native ABI: ARM64-v8a only (no 32-bit device support)

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
