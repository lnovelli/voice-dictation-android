# Live Parakeet TDT VAD chunking — v1.2.5 / build 26

## Current chunk policy

For live TDT v2/v3, close and queue the current contiguous range at a detected pause after at least five seconds of cumulative, confirmed positive speech, or after observed speech followed by approximately three seconds of VAD-negative audio regardless of speech duration. VAD examines 32 ms frames, retains candidate speech and waits for active speech to end. Reset cumulative speech at each queued boundary. At Stop, a tail of at least one second is always decoded; a shorter tail is skipped only when VAD reports no possible speech. If VAD is unavailable or fails, decode the remaining range. The complete original PCM is retained separately, and ordered worker queues and cancellation behavior are unchanged. VAD can miss quiet speech; long speechless tails can produce spurious words. Neither rule imposes a hard maximum chunk duration, so long uninterrupted speech remains a risk.

For each VAD chunk in live TDT and History VAD reprocessing, the decoder drops the classified negative tail after the last complete 32 ms frame with speech probability at least 0.5. There is no additional 200 ms trailing margin or confirmation delay. If no complete frame is positive, trailing-edge trimming leaves the original chunk intact, though optional internal-silence removal can still apply. An unclassified final partial frame (less than 32 ms) is preserved even if the classified negative tail before it is removed, which can create a discontinuity. VAD errors and the cut can lose quiet speech or word endings. The captured PCM and saved audio are unchanged. This trailing-edge trim is separate from the optional, default-off internal silence removal: that option still removes parts of qualifying VAD-negative runs and protects the last 200 ms of each run and the first 200 ms of each chunk before the trailing-edge trim.

The **Always dither TDT (experimental)** setting is off by default. When enabled for a new recognition session, fixed feature dither `0.001` (low-level noise during feature extraction) applies to normal decodes and retries for TDT v2/v3 live recording, History reprocessing and completed files. It can change output or produce hallucinated words; review important output. It does not affect Nemotron streaming, VAD or the saved original audio.

Even when that setting is off, if a TDT decode produces empty ASR text, the decoder automatically retries it once with feature dither `0.001`, unless the expanded input exceeds the decoder limit. Ordinary non-VAD reprocessing and completed-file TDT decoding use up to 100 ms of original adjacent recording audio on each side, zero-filling missing context at recording edges, without extra outer silence. VAD chunk retries, including History's experimental remove-silence option, retain left context but omit right-side context when it would restore trimmed trailing silence. Context may be silence, not speech, and overlap with a neighboring chunk may duplicate words. A retry does not guarantee a transcript or improved accuracy and can produce hallucinated words. The saved original audio remains unchanged. Nemotron streaming does not use this retry.

The following describes the historical v1.2.5 implementation; its chunk thresholds and no-trimming behavior are superseded by the current policy above.

This release affects **live microphone recording with Parakeet TDT v2 or v3 only**. Parakeet Unified streaming, completed-file transcription, and History reprocessing remain on their existing paths. v1.2.3 used one full-recording TDT decode after Stop.

## Capture and chunk ownership

- AudioRecord writes the authoritative mono 16 kHz PCM16 into the bounded 10-minute capture buffer. Completed-file IPC input remains capped at 120 seconds.
- Capture publishes contiguous 1,024-sample range descriptors to a bounded VAD-input queue. The queue can retain descriptors for the entire capture; microphone reads do not wait for VAD or TDT inference.
- A separate Silero worker examines every 512-sample frame. It accumulates at least five seconds of positively VAD-classified speech, excluding candidate onset, hysteresis-only frames, and silence hangover. Short pauses do not reset this speech total.
- At a speech-to-silence endpoint, the worker releases the contiguous original-PCM range only when that accumulated speech total is at least five seconds. Until then, the range—including all pauses—stays open across later utterances. This avoids decoding small chunks without removing or filtering their audio.
- Chunks are non-overlapping and ordered. At explicit Stop the still-open tail, including any incomplete VAD frame, is queued exactly once even if it has less than five seconds of speech.
- Both queues are sized for the full 10-minute cap. The serialized decoder processes every queued range in sequence. If decoder throughput falls behind, the backlog waits; no chunk is intentionally skipped or replaced with a newer one.

Silero `maxSpeechDuration` is **15 seconds for live TDT chunking**, but in sherpa-onnx 1.13.8 this is not a hard split timer. After the active VAD buffer exceeds that duration, Sherpa's Silero policy becomes more aggressive (threshold 0.9 and minimum silence 0.1 seconds); it still waits for classified silence. TDT mode uses the bundled Silero V4 `compute()` API to avoid Sherpa retaining/copying a growing active segment and mirrors its per-frame hysteresis. Its 15-second trigger is approximate: this implementation measures from the first speech candidate, whereas Sherpa measures the retained buffer including preroll. Non-TDT visual-only capture keeps the existing 5-second segmenting VAD behavior.

## Finalization and failure handling

Chunk ASR produces no partial UI, IPC, history, or insertion results. After Stop, all queued TDT work is drained, text is assembled in range order, then optional polishing runs **once** on the complete transcript, followed by the existing local text rules and delivery. Final text settings are snapshotted at Stop.

If VAD is unavailable or fails, the unsegmented remaining PCM is still decoded. If TDT chunk decoding or range validation fails, the worker drains its queue and retries once with the complete original capture before any transcript is accepted. Cancellation suppresses active/queued results and all final side effects; native ownership remains held until an in-flight JNI call returns.

## Limitations and verification

Each offline TDT chunk resets decoder context. Five seconds is a VAD-classified speech threshold, not guaranteed ground-truth speech; adaptive VAD endpoints still require a pause and are not hard time splits. Ten-minute capture retains up to 19.2 MB of raw PCM before recognition buffers and model memory. Recognition, punctuation, latency, and phone power use have **not** been validated on-device; no quality improvement is claimed. The earlier published v1.2.4 APK remains available as a prior release.
