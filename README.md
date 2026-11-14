# Voice Dictation for Android

An on-device, privacy-first voice dictation application for Android powered by NVIDIA Nemotron streaming through sherpa-onnx, with optional ML Kit GenAI Speech Recognition Advanced on supported devices.

Voice Dictation brings seamless system-wide voice input to any text field across Android without sending audio data to third-party cloud servers.

---

## Current release: v1.2.48 (build 69; ARM64-only)

Adds owner-bound remote PCM streaming for compatible clients, with Nemotron interim previews and final byte-count and SHA-256 validation. This release has not been tested on a device. Device behavior and recognition accuracy depend on the app and device; backup and restore depend on Android's transport.

Settings JSON is unencrypted and may contain private vocabulary, endpoints and prompts. It excludes transcript history, audio, downloaded models and system permissions. Android cloud backup may include settings and transcript history, but excludes models and saved audio.

## Features

- **Local & Private Inference:** Runs locally on your Android device using `sherpa-onnx` and ONNX Runtime Mobile with ARM NEON CPU acceleration, or ML Kit GenAI Speech Recognition Advanced on supported devices.
- **State-of-the-Art ASR Models:**
  - **Nemotron 3.5 ASR 0.6B (Multilingual, Streaming):** Choose a 560 ms or 1,120 ms model chunk size. Language is detected automatically; model chunk size is not an end-to-end latency guarantee. The app returns final text on Stop.
  - **ML Kit GenAI Speech Recognition Advanced:** Available only on supported devices, after checking model availability and downloading if needed. Choose a speech language. Acoustic word boosting applies only to Nemotron.
- **One-Click Model Download:** In-app model downloader with real-time progress, background transfer, and automatic local archive extraction.
- **Smart Floating Overlay Button:**
  - In normal mode, a focused, enabled editable accessibility node in an application window can show the button even without an Android 13+ input-session callback. With a matching Android 13+ input session, a focused, enabled node advertising a text-setting action or reporting an EditText or AutoCompleteTextView class is also accepted when it reports itself non-editable. An unavailable or unfocused node can retain visibility for at most 250 ms after a confirmed editor, only in the same session and window; window changes cancel the grace. Apps can report stale or incomplete state, so the button can still appear incorrectly or be absent for some custom editors. Older Android versions require a focused, enabled editable node.
  - When idle, hold the floating button for 1.5 seconds to choose an available microphone or **System default (automatic)**. Android controls the final routing and does not reliably identify the default input before capture; unavailable, rejected or mismatched explicit choices are reported. This selection also applies to live external dictation, not saved audio reprocessing. During recording, hold to discard as before.
  - On Android 13+, system input-start/finish callbacks help identify supported custom editors; the app does not read typed text or cursor positions to decide visibility.
  - Optional **Keep button visible while unlocked** mode supports apps that do not expose editable focus to accessibility services. It can overlap sensitive screens; Android can still hide overlays when an app explicitly blocks them, and inaccessible fields may require clipboard paste.
  - Draggable partly beyond any screen edge, with independent saved positions for portrait and landscape; an unset position starts vertically centered at the right edge.
  - Optional Nemotron streaming preview shows interim words in a separate draggable overlay, up to two lines. ML Kit live preview is not implemented. It is local-dictation only; preview text is visible on screen, may change before the final result, and clears when recording ends.
  - Adjustable floating icon size (50–150%) and opacity, applied immediately from Settings.
  - In normal focus-triggered mode, the button generally hides when focus moves away from text fields, but stale accessibility information can keep it visible; the optional always-visible mode stays on while the device is unlocked.
  - Settings can disable autofill/competing-window collision suppression. Dictation inserts only into the field focused when recording started; if focus changes or no field was focused, the result is copied to the clipboard with a brief toast.
  - Local dictation uses the original focused editor's paste action at its current cursor or selection, without adding spaces. On Android 13+, an input-session-matched custom editor advertising a text-setting action can also be a target. The original node must still be the focused node in the same window when dictation finishes; otherwise the transcript is copied for manual paste. This replaces the clipboard contents with the transcript; if the editor refuses paste, the transcript remains on the clipboard for manual insertion. A text-setting action is used only as evidence of an editor, never to replace the field's contents.
  - **Floating button diagnostics** in Settings records an opt-in, temporary timeline focused on visibility decisions, their triggers and reasons, state changes, and insertion outcomes. Repeated identical checks are summarized instead of logging each raw event. Start a session, switch to the affected app to reproduce the issue, then return to inspect or copy it. It keeps at most 500 entries in memory, stops after 10 minutes, and is lost if the app process dies. It does not record field contents or transcripts, but app package names and other metadata can be sensitive; Android may prevent identifying some packages. Users can opt in per session to include provider-supplied, unverified package-like strings, which may contain sensitive data and are not proof of an app's identity. Copying exposes the report to the clipboard. The app's requested/view visibility does not prove that Android displayed the overlay.
- **Capture and recognition:** Settings allow 2, 3 or 4 Nemotron recognizer threads (default 2). Nemotron consumes captured audio while recording; Stop drains and flushes the stream. Recognition returns final text, with an optional local live preview. Original PCM is retained for History. Device throughput and recognition accuracy depend on the phone and model.
  - Hold the recording button during capture or final processing to discard and suppress the result. An already-running native decode cannot be interrupted, but its output is discarded.
  - At 10 minutes, microphone capture closes and waits for explicit Stop or Cancel.
- **Custom Vocabulary:**
  - Add terms, names or acronyms in the Vocabulary tab. The spelling switch only recases or joins/splits spaces when the recognized letters already match.
  - Optional experimental acoustic word boosting biases Nemotron decoding toward saved phrases. Boosting can change unrelated words or produce hallucinations; review important text.
- **Custom String Replacements:**
  - In the Vocabulary tab, map multiple literal transcription variants to one output phrase. Matching is case-insensitive and respects whole-word/phrase boundaries; replacements are applied automatically to final transcripts and remain independent of acoustic word boosting.
- **Transcript formatting and polishing gates:**
  - Polish settings can convert clear English number phrases to digits (off by default), set the AI-polishing minimum to 0–20 words (default 10), and optionally polish short transcripts containing nearby number sequences or the whole word “one”. Standalone “one” is left spelled out by static conversion; compound numerals such as “twenty one” still convert.
  - The word-count threshold (0–20, default 10) is evaluated before number-word conversion. When auto-polishing is enabled, transcripts below the threshold trigger polishing for nearby-number sequences such as “4 30 pm” or “16 oh five”, and for “one” outside the idiom “one another”; the model uses context to decide whether “one” should become `1`. The master AI-polishing switch must also be enabled.
- **Optional AI Transcript Polishing:**
  - The Polish tab configures and tests an OpenAI-compatible chat-completions endpoint and model. Defaults are `http://127.0.0.1:11434` and `default`; the master switch is off until explicitly enabled.
  - Sends your saved system prompt, fixed input/output instructions, ordered transcript chunks and custom vocabulary as text, never audio. A custom prompt also receives the combined transcript for compatibility. HTTP is restricted to `127.0.0.1`/`localhost`; external hosts require HTTPS. A bounded timeout, response-size limit, and no-redirect policy apply.
  - The built-in prompt preserves the speaker’s meaning, language and tone while lightly fixing grammar, syntax and clear recognition errors. It uses chunk boundaries as hints rather than sentence boundaries, including joining and separating sentences, and corrects “up” to “app” when context warrants it. Vocabulary is only a context-supported spelling/recognition hint. Configured local filler-removal and replacement rules still run afterward. Saved custom prompts are retained; restore the built-in prompt to use the updated editing instructions.
  - Polishing requests a strict JSON-schema response. Server support is required: HTTP, timeout, malformed, non-schema or empty responses retain the original ASR transcript and apply existing local post-processing. The synthetic connection test does not check JSON-schema support.
- **Editable Final-Transcript Removal List:**
  - Enabled by default with conservative fillers `um`, `uhm`, `uh` (not ambiguous `ah`, `like` or `so`). The Polish tab provides a switch, one-word/phrase-per-line editor, Save and Restore Defaults.
  - Removes literal case-insensitive Unicode whole words/phrases only, never substrings such as `um` inside `album`. Apostrophe/hyphen words and combining marks are protected; punctuation and spacing left by removals are cleaned conservatively.
  - Applies only after the single final decode, identically to floating/live/file results and history. Disable or save an empty list to preserve text. If everything is removed, normal no-speech ERROR safety applies; no empty result is submitted.
- **Transcription History:**
  - Automatically stores recent transcripts with timestamps and saves the complete original recording as one WAV alongside each new successful entry, up to the live capture limit (currently 10 minutes). The History tab can play saved audio or reprocess it to replace the existing transcript while keeping its ID, timestamp and recording.
  - **Reprocess** uses the selected model and current text-processing settings. It never pastes or submits the result. If AI polishing is enabled, the app asks before sending transcript text (never audio) to the configured server.
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
4. **Download Model:** In **Settings**, choose a Nemotron streaming model and tap **Download Model**, or select ML Kit Advanced where supported and download its model if necessary.

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
