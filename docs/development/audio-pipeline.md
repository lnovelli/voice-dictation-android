# Capture and recognition lifecycle

`SpeechEngine` owns capture and native recognition. Original bounded 16 kHz mono PCM is authoritative, retained independently of decoder input and saved for successful History. Never silently drop samples, mutate saved PCM, auto-submit a transcript or infer recognition accuracy from complete range coverage. Capture queues must hold the full capture cap so slow inference cannot block the microphone. Retain ownership until in-flight recorder reads and native operations have drained; an active JNI call may not be interruptible.

## Streaming and stop

For Nemotron, use one online stream for a whole live session or saved file. Feed all PCM, pauses included, in 200 ms blocks on a separate worker; snapshot the active model per session. Flush a shorter final block without waiting for it to fill. The feed block size is distinct from the model's chunk size. When explicitly enabled, local dictation may display interim hypotheses in its separate two-line overlay. Remote PCM can also deliver replaceable unvalidated PARTIAL IPC previews to its owner; local live and file IPC cannot. At Stop snapshot final text settings, drain backlog, feed finalization silence separately from saved PCM, signal `inputFinished`, drain ready frames and return one final result without a second full-file decode. InputFinished alone cannot necessarily flush incomplete NeMo encoder chunks. Cancel skips finalization and result but waits for native ownership to be safely released. A slow device can make Stop wait for backlog; no latency or quality guarantee follows from host checks.

Remote PCM uses the same online worker, without AudioRecord. An IO reader preserves
raw samples and sends bounded blocks; it accepts at most five minutes and handles
split 16-bit samples. It waits for both owner FINISH and pipe EOF, validates the
independent count and SHA-256, then releases the final short block and permits decoder
flush. Failed or cancelled input is never saved in History or published as a result;
earlier previews are unvalidated and must not be persisted or submitted. A bounded
nonblocking pipe poll and descriptor closure allow cancellation while a writer stalls.

ML Kit GenAI Speech Recognition Advanced is an alternative, device-dependent engine; do not silently substitute Basic. Verify its current status before inference and keep its recognizer, input and cancellation ownership scoped to the current session. The app currently uses one-shot input and does not implement ML Kit streaming preview, even though the SDK supports streaming; it waits until recording stops and uses the same original PCM and final result pipeline. The selected speech locale is explicit; it does not imply automatic language detection. ML Kit model management belongs to the platform, not the Nemotron downloader.

ML Kit diagnostics retain at most 40 sanitized status, phase, known exception type and numeric error-code events in process memory. For alpha2 speech errors, match the entire SDK-generated message against fixed known strings and record only the matching fixed error-type token; do not store the message itself. They never contain audio, transcript text, arbitrary exception messages or endpoints; they disappear when the process ends and are copied only on user action in Settings. Runtime `ErrorResponse` codes must not be discarded. A successful build or readiness status does not establish that device recognition works.

For explicitly authorized system-log diagnosis, bound the capture window and filter in memory to fixed allowlisted indicators without saving raw lines. An unmatched indicator does not rule out a cause.

At the live capture cap, stop/release the microphone, retain PCM and wait for explicit Stop/Cancel, with a cue; do not finalize or submit automatically. Trace the IPC deadline and client timeout when changing the cap. A notification shade or transient focus loss does not cancel capture, while screen-off/lock and local service teardown follow the local safety policy; they must not hijack external ownership.
