# Live dictation review — 1.0.6 / code 7

Historical review. The single 120-second native final decode and vocabulary bias
below are superseded by [1.0.7’s verified ASR regression fix](asr-regression-1.0.7.md).
PCM ownership, cancellation, shade handling and IPC security remain in place.

## Concrete faults found in the previous implementation

- Live final text was `completed.joinToString(" ")` from separate VAD segment decodes. `flush()` did not provide a second full-recording decode. The 350 ms silence boundary / eight-second max-speech segmentation could split a sentence and deprive each decode of preceding/following context.
- The preview `SampleWindow` discarded samples after completed VAD segments and retained only half a second when inactive. There was no independent authoritative recording, so that discarded context and pauses were unavailable to final inference. Final short reads were padded only for VAD rather than preserved independently at exact length.
- The bounded 320-frame queue failed a session if a native decode fell about ten seconds behind capture. More inference could therefore destroy an otherwise usable recording.
- Notification expansion triggers accessibility `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED`. `rootInActiveWindow` can then be SystemUI without an editable input. `handleFocusEvent()` cleared the target and called `OverlayManager.hide()`, which unconditionally called `SpeechEngine.cancelRecording()`. This is a concrete provider-side cancellation path; it does not justify weakening the phone client's real-background `onStop` cancellation.

## New ownership and buffering

1. Capture writes each successful microphone read into a fixed `ShortArray` of 1,920,000 samples (3,840,000 bytes, 120 seconds at mono 16 kHz PCM16). It preserves all captured pauses, short reads and VAD remainders in order. It does not promise recovery of device-side unread audio or microphone/OS interruptions.
2. A separate bounded queue can hold the entire recording as 512-sample float preview frames (the last preview frame alone may be zero-padded). The queue cannot overload merely because one inference call exceeds ten seconds. Capture stops automatically at the PCM cap.
3. Preview code retains a rolling 12-second window, never completed text segments. VAD only gates preview attempts; completed VAD segments are popped, not decoded or stitched. Previews are attempted at least two seconds apart, skip stale backlog, and stop on a recoverable preview error. A VAD initialization/reset failure does not make it the final transcription authority.
4. STOP drains captured data and joins capture before converting the exact PCM count to one final float waveform. That single native decode is the only final text. It runs even when VAD found no segments; the model can still misrecognize silence/noise. Final failure never publishes a preview.
5. Java/Kotlin audio buffers are bounded: about 3.84 MB PCM, up to 7.69 MB preview queue, 0.77 MB rolling preview, 0.77 MB preview snapshot, and 7.68 MB final waveform at their respective peaks (decimal MB, excluding object overhead). Native model/feature/encoder memory is additional and potentially large; a 120-second decode can still exceed memory on constrained hardware. This is not a total-process memory guarantee.
6. All recognizer/VAD creation, inference, stream release/reset and model destruction remain inside the session's native mutex, off the UI thread. Capture alone runs concurrently without native ASR access. Models are detached before release, both objects get a release attempt, and repeated active release requests share the same release job.
7. CANCEL sets a flag and stops the microphone, closing an owned file descriptor promptly. No job cancellation releases native objects under inference. Cancellation is checked before final inference/delivery, and ownership remains reserved until the job completes. A running native decode is not forcibly interruptible. Unexpected coroutine failure cannot be delivered as success. Ordinary overlay controls cannot cancel external owner tokens.
8. Native exceptions that surface as recoverable Kotlin exceptions can be reported; fatal JNI aborts, process death and OS low-memory kills cannot be repaired by Kotlin error handling.

## Overlay lifecycle and protected IPC

While a local session is active, lack of an editable accessibility root does not call `hide()` or clear its retained target. The existing stop control remains attached; idle focus hiding is re-evaluated after final delivery. `hide()` itself still cancels, as do accessibility interruption/destruction, disable and the newly explicit screen-off receiver. Keyguard/interactive-screen checks apply to focus handling and insertion. If SystemUI still obscures the old field on completion, do not modify it; ordinary floating results use clipboard/history fallback while unlocked. Screen lock does not copy or paste a result.

Overlay geometry, signed off-screen position, opacity/scale, settings, models, vocabulary and hotword decoding options are unchanged. The visible local overlay requests screen-on during loading/capture/finalization and clears it on idle/hide/destruction; manual screen-off still cancels. External IPC retains its signature permission, exact package/UID checks, callback Binder identity, owner token, death/unbind cancellation and no clipboard/accessibility result delivery. Command 4 and protocol 1 retain the 120-second / 3,840,000-byte regular PCM file contract and single final decode.

The total external session deadline is ten minutes, still bounded and cancellation-protected. Recommend a five-minute phone-client final-processing deadline after STOP (also handle automatic processing at 120 seconds), without relaxing actual-background cancellation. This is not a latency guarantee; a very slow native call can outlast cancellation/deadlines, retaining engine ownership until return.

## Verification

- `./gradlew assembleDebug lintDebug --console=plain`: BUILD SUCCESSFUL; APK is `app/build/outputs/apk/debug/app-debug.apk`.
- No unit tests created or run. Android lint also statically analyzes existing test sources; this is not test execution.
- No connected-device checks performed: recognition quality, real VAD transitions, notification/quick-settings gestures, lock-screen cancellation, 120-second memory/latency, OEM microphone behavior, hotwords, signature rejection, Binder death/unbind, and watch file descriptors still need device verification.
- Accuracy is intended to benefit from full context, but is not guaranteed. Check important transcripts. No commit, push or publication is performed by this change.
