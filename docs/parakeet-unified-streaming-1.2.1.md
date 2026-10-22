# Parakeet Unified streaming — v1.2.1 / build 22

## Model selection and download

Settings adds **Parakeet Unified 0.6B (English, Streaming)**. Its assisted download uses the sherpa-onnx 1.13.8 560 ms buffered-streaming model archive and verifies the required encoder, decoder, joiner and token files before reporting ready. The model is English-only. Its advertised 560 ms is a model context preset, not a guarantee of end-to-end phone latency.

## Live microphone behavior

- When Unified is selected, `AudioRecord` continues capturing exact 16 kHz mono PCM while a separate worker feeds bounded 500 ms PCM batches to sherpa-onnx `OnlineRecognizer`. It keeps the online decoder state across batches and decodes each ready chunk.
- Cold model initialization happens before microphone capture starts. While recording, partial hypotheses remain internal; no transcript preview or partial IPC event is emitted.
- Explicit Stop drains the in-flight microphone read and queued ASR input, marks the online stream finished, drains ready frames and uses that one final online result. It does not run a second full-recording decode.
- The ASR queue is bounded to hold at least the entire 120-second capture, so a slower device does not force the microphone thread to drop audio. If inference falls behind, Stop waits for the queued work; real-device throughput has not been verified.
- The complete raw PCM recording is retained independently for successful History entries. Cancel during capture stops the microphone and skips stream finalization; Cancel during post-Stop backlog draining suppresses the final result. An individual JNI call already in progress cannot be interrupted and may delay cleanup.
- Final-only spelling hints, string replacements, filler removal and optional text polishing run after ASR finalization. Text-transform preferences are snapshotted at Stop, not at capture start.

## Other models and paths

Parakeet TDT v2 and v3 remain offline-only in this app: recognition does not run during microphone capture, and explicit Stop starts their existing single full-recording decode. Their acoustic hotword option remains separate; acoustic hotword bias is not applied to Unified. Completed-file and History reprocessing use the selected recognizer, feeding Unified audio in chunks and flushing once at the end.

The Android Hermes integration continues to receive only the final RESULT after processing. Protocol 1 and its reserved PARTIAL event are unchanged.

## Verification limits

A successful APK build and static checks do not establish recognition latency, memory use, microphone behavior, transcript quality or cancellation behavior on a physical Android device. Benchmark the selected model on target phones before treating the 560 ms preset as experienced latency.
