# Record then transcribe — 1.0.8 / versionCode 9

## Current contract

- Live capture writes exact mono 16 kHz PCM16 samples directly into a fixed 1,920,000-sample buffer. Every successful short read is preserved; no padding, VAD trimming, queues, preview models or preview ASR. Microphone/OS failures are errors, not consent to transcribe incomplete audio.
- Native initialization is deferred until explicit Stop. Already-cached ASR models may remain resident between sessions, but no inference runs while recording or waiting at the cap.
- Stop closes capture, converts only the captured sample count, loads/reuses the selected model, and selects one contiguous final interval. Only exact-zero leading/trailing PCM may be removed, retaining up to 500 ms of original audio around every nonzero sample; all internal pauses remain intact. One fresh native stream receives that whole interval. There is exactly one `engine.decode(stream)` call, no window partitioning, final-text stitching, retry fallback or repetition repair. All-zero recordings stay unchanged. This final-only endpoint rule never runs during capture.
- Both live and command-4 files use `greedy_search`, empty hotwords file, hotwords score zero and blank penalty zero. Existing optional spelling-only capitalization/spacing hints are applied after decoding; they cannot affect acoustic token selection. The Settings spelling switch remains; unsafe acoustic bias stays disabled.
- At 120 seconds the microphone is stopped and released. PCM stays bounded and waits on an explicit Stop/Cancel signal. The cap does not set Stop and cannot initiate model initialization or decoding. Local UI shows a limit toast, stops the recording pulse, and retains the Stop icon and discard gesture.
- Cancel during capture/cap-wait discards without decoding. Disable, explicit local hide, screen-off/lock, accessibility interruption/destruction, release, external CANCEL/death/unbind/timeout all retain their ownership-specific cancellation paths. Cancellation during native initialization/inference cannot interrupt the executing native call; output is discarded and ownership/native mutex remain reserved until return and cleanup. Fatal native aborts/process death/OOM are not recoverable Kotlin errors.
- Native creation, decode, stream release and destruction remain serialized off Main. Capture does not hold the native mutex. Live recording and pending decisions remain exclusively owned; another session is rejected as busy.
- Command 4 reads an already-complete bounded regular PCM file directly, closes its descriptor, and processes immediately. It never opens the phone microphone or waits for Stop; owned file STOP is a no-op, CANCEL still discards. Signature/package/UID/Binder validation is unchanged.
- Readiness/downloads require encoder, decoder, joiner and tokens only. Silero VAD is neither downloaded, checked nor initialized. Existing model directories stay valid, and previously installed VAD files are ignored rather than deleted.

## Editable final-only transcript removal

Settings includes an enabled switch (default true), a multiline one-entry-per-line
editor, explicit Save, and Restore `um`, `uhm`, `uh`. `ah`, `like` and `so` are not
defaults because they can be meaningful speech. An explicitly saved empty list stays
empty across launches. Up to 100 literal entries of 80 characters each are accepted;
regex syntax is not accepted. Invalid/oversized entries show an error rather than
silently truncating the user's list. The filter matches case-insensitive Unicode
whole words/phrases with whitespace between phrase words, longest phrases first.
Letters, combining marks, numbers, connectors, apostrophes and hyphens block
substring removal, protecting words such as `album`, `uh-oh` and `uh’s`.

`SpeechEngine.initializeEngine()` snapshots the removal setting/list at finalization,
even when the recognizer is cached. `decodeRecording()` applies it after spelling
hints, once, to the final decoded text. All paths therefore share filtered history,
local insertion and IPC RESULT. Filler-attached commas/semicolons and orphan spacing/
punctuation are cleaned; untouched transcripts are returned verbatim. All-filler
output is empty and follows existing no-speech ERROR handling without inserting,
copying, storing or submitting an empty result. This changes only final text, never
audio or token selection. Disable the switch to retain recognized filler words.

A direct console probe against the compiled production `TranscriptFilter` (ignored
`app/build/asr-diagnostics/TranscriptFilterProbe.java`, no unit-test framework)
returned `hello, world.` for `Um, hello, uh, world.`, empty for `Um, uhm; UH!`,
kept `album hum uhmatic uh-oh uh’s résumé` intact, removed mixed-case `You KNOW`
as a phrase, matched `Éh` but not the substring in `préÉh`, and preserved disabled/
empty-list output. The initial console run found a bracket-regex error; it was fixed
and the production code rebuilt/re-exercised successfully.

Native acoustic bias remains **blocked, not restored**, after 303 additional native
decodes. Exactly-one-path score 1 changed v2 `Grafan not` to `Grafana,` without text
correction, but lost an unrelated weather sentence in v3 over a 20-second internal
pause. Smaller scores had weak/no desired lexical benefit in these samples. The
capability labels now explicitly distinguish unavailable acoustic custom recognition
from optional spelling-only hints. See [native context and endpoint evidence](acoustic-context-and-endpoints-1.0.8.md).
The single-pass spelling/removal pipeline, cancellation gates and native mutex remain.
Do not restore unchecked hotword score 2.0 or claim spelling fulfills acoustic bias.

## Local gestures and safety

- Idle short tap starts recording; recording or cap-wait short tap Stops and transcribes.
- A stationary recording hold uses Android's long-press timeout and cancels/discards. It provides haptic, accessibility announcement and toast feedback. Its release is consumed even if cleanup makes the engine idle before UP; no restart click can follow.
- Moving beyond touch slop cancels the hold timer and starts a drag. UP saves the signed/clamped position, never transcribes. ACTION_CANCEL cancels only the gesture (not the recording), and multi-touch cannot become a stray click. Hide/destruction removes pending hold callbacks and consumes stale release.
- Accessibility click/long-click exposes the same Stop/discard actions and state descriptions. Overlay controls cannot operate externally owned sessions.
- Existing scale/opacity/partial-offscreen reachability and rotation clamping are retained. Notification/quick-settings focus changes do not cancel local capture or cap-wait; explicit screen-off/lock still does. Insertion never targets a SystemUI-obscured old field; locked state never copies/pastes.

## Parent Hermes client changes required

Protocol stays **1**, callbacks remain STATE **101**, PARTIAL **102** (reserved, never emitted), RESULT **103**, ERROR **104**.

| Provider STATE | Engine flags (`isRecording`, `isProcessing`, `isAwaitingStop`) | Client meaning |
| --- | --- | --- |
| `recording` | true, false, false | Capture accepted; retain Stop/Cancel. Microphone startup errors can still follow. |
| `recording_limit` | true, false, true | Microphone released, PCM retained, waiting for explicit Stop/Cancel. Map to recording/Stop-ready and show a limit toast. |
| `loading` | false, true, false | Finalization/model initialization after live STOP, or completed-file processing. Do not restart capture. |
| `processing` | false, true, false | One full-recording native decode. |

Live sequence: START → recording → optional recording_limit + wait → STOP → loading → processing → RESULT/ERROR. Empty/immediate Stop may go directly to ERROR. File sequence: command 4 → loading (possibly repeated) → processing → RESULT/ERROR. States may repeat; no idle event is required. Do not expect, display or submit PARTIAL. Only RESULT is submitted once after complete cleanup.

Keep the ten-minute total IPC deadline, including user decision wait, and recommended five-minute post-Stop/file-processing client deadline. Do not auto-Stop at the cap or start processing timers on `recording_limit`. Genuine activity backgrounding must still CANCEL/unbind; do not weaken death/signature/ownership checks.

## Execution evidence and limitations

- `ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug lintDebug --console=plain`: passed. No unit tests created or executed; lint statically examines existing test sources. Lint reported 63 warnings, no errors. APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `adb devices`: no connected devices. Android microphone start/Stop/cap/cancel behavior, gesture timing/haptics/TalkBack, lock/shade/OEM handling, physical-device memory/latency and protected IPC need device acceptance. Linux native recognition is not Android end-to-end evidence.
- Manual native probes used the existing ignored `app/build/asr-diagnostics/venv`, sherpa-onnx **1.13.8**, official v2/v3 int8 models and their public test WAVs. One stream/decode per complete waveform; CPU, two threads, feature dimension 128, greedy, no hotwords/bonus/blank penalty. The corrected `fullpass_pcm_probe.py` uses ffmpeg to convert official v3's 22.05/24 kHz samples to the exact app format, signed 16 kHz PCM16, before conversion to floats. These are manual diagnostics, not unit tests.
- v2: exact 120-second digital silence (1,920,000 samples) returned empty text/zero tokens in 22.799 seconds. Official 7.435-second speech followed by silence to exactly 120 seconds returned 48 tokens in 22.975 seconds: “We didn't wish to see it any more, observed Phoebe, turning away her eyes. It is certainly very like the old portrait.”
- v3 corrected 16 kHz PCM: standalone English (3.845125 seconds) returned “Ask not what your country can do for you, ask what you can do for your country.” French (4.969125 seconds) returned “Ne vous demandez pas ce que votre pays peut faire pour vous. Demandez-vous plutôt ce que vous pouvez faire pour lui.”
- **Initially reproduced, now partially mitigated:** untrimmed v3 English/French speech followed by digital silence to exactly 120 seconds returned empty text/zero tokens (23.078/23.013 seconds), although each short clip transcribed normally. Separate 120-second digital silence returned empty text (22.853/22.755 seconds). Exact app-format PCM conversion did not fix it. The new compiled `FinalAudioPolicy` exact-zero endpoint selection recovers both languages with one contiguous native decode, preserving all nonzero PCM and up to 500 ms margins; centered speech in 120-second zeros also recovers. This is not a general long-recording fix: stationary low-level noise tails and long internal pauses still cause v3 empty/lost speech. An RMS gate was rejected because it discarded quiet spoken samples. V3's model description and empty-result error disclose the limitation; no retry, window, partial or synthesized fallback is used. See the linked native context/endpoint evidence for full observations.
- The old v2 hotword pathology (421 `Nextcloud`-biased tokens on silence) did not return in the unbiased full-120-second silence probes. This does not prove all noise/silence inputs are hallucination-free, or establish real 120-second continuous-speech accuracy.
- Native logs/probes are ignored under `app/build/asr-diagnostics/`: `v1.0.8-v2-full120.jsonl`, `v1.0.8-v3-{en,fr}-pcm16k.jsonl`, corresponding stderr, `fullpass_pcm_probe.py`, and `build-1.0.8.log`. No commit, push, publication, phone/watch edit or unit-test execution.
