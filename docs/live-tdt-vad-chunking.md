# Live Parakeet TDT VAD chunking — v1.2.4 / build 25

This release affects **live microphone recording with Parakeet TDT v2 or v3 only**. Parakeet Unified streaming, completed-file transcription, and History reprocessing remain on their existing paths. v1.2.3 used one full-recording TDT decode after Stop.

## Capture and chunk ownership

- AudioRecord writes the authoritative mono 16 kHz PCM16 into the existing bounded 120-second capture buffer.
- Capture publishes contiguous 1,024-sample range descriptors to a bounded VAD-input queue. The queue can retain descriptors for the entire capture; microphone reads do not wait for VAD or TDT inference.
- A separate Silero worker examines every 512-sample frame and emits an endpoint on a speech-to-silence transition. Each TDT work item references a range in the original capture buffer; it neither copies VAD's speech-only segments nor discards silence.
- The endpoint is the processed-frame cursor after the silence needed to confirm the pause. Chunks are non-overlapping and ordered. At explicit Stop the still-open tail, including any incomplete VAD frame, is queued exactly once.
- Both queues are sized for the full 120-second cap. The serialized decoder processes every queued range in sequence. If decoder throughput falls behind, the backlog waits; no chunk is intentionally skipped or replaced with a newer one.

Silero `maxSpeechDuration` is **15 seconds for live TDT chunking**, but in sherpa-onnx 1.13.8 this is not a hard split timer. After the active VAD buffer exceeds that duration, Sherpa's Silero policy becomes more aggressive (threshold 0.9 and minimum silence 0.1 seconds); it still waits for classified silence. TDT mode uses the bundled Silero V4 `compute()` API to avoid Sherpa retaining/copying a growing active segment and mirrors its per-frame hysteresis. Its 15-second trigger is approximate: this implementation measures from the first speech candidate, whereas Sherpa measures the retained buffer including preroll. Non-TDT visual-only capture keeps the existing 5-second segmenting VAD behavior.

## Finalization and failure handling

Chunk ASR produces no partial UI, IPC, history, or insertion results. After Stop, all queued TDT work is drained, text is assembled in range order, then optional polishing runs **once** on the complete transcript, followed by the existing local text rules and delivery. Final text settings are snapshotted at Stop.

If VAD is unavailable or fails, the unsegmented remaining PCM is still decoded. If TDT chunk decoding or range validation fails, the worker drains its queue and retries once with the complete original capture before any transcript is accepted. Cancellation suppresses active/queued results and all final side effects; native ownership remains held until an in-flight JNI call returns.

## Limitations and verification

Each offline TDT chunk resets decoder context. Pausing at VAD boundaries should avoid many word splits, but recognition, punctuation, latency, and phone power use have **not** been validated on-device; no quality improvement is claimed. The exact PCM coverage and bounded-queue behavior are statically checked in code, and the project build passed. The earlier published v1.2.3 APK remains available as a prior release.
