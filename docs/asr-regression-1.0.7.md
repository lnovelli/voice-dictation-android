# ASR regression diagnosis and fix — 1.0.7 / code 8

## Outcome and confidence

The severe custom-word repetition is **reproduced in actual native decoding**, not inferred from a text heuristic. Linux `sherpa-onnx==1.13.8` with the official Parakeet v2 int8 model, this app’s BPE vocabulary, `modified_beam_search`, four paths and hotword `Nextcloud` at the app’s default score **2.0** emits **421 tokens on twelve seconds of digital silence**. Both greedy and beam without a hotword return empty text. The repeated word was a public diagnostic choice, not taken from the user's private vocabulary/audio.

This establishes a concrete failure in the old configuration that matches the reported symptom. There is no private failing recording available, so this does not establish the precise contents/acoustics of the user's failure or guarantee every ASR error has the same cause.

Full observed configurations, exact transcripts (including the complete hallucination), timings, stderr, compiled Kotlin partitions and file hashes are preserved in [asr-native-observations.json](asr-native-observations.json). All observations here are host CPU results, **not Android hardware results**.

## Model and decoder findings (pinned upstream 1.13.8)

- `modelType="nemo_transducer"` is correct for both configured model archives. Their encoder metadata is named `EncDecRNNTBPEModel`, but this is **not evidence of RNNT-only decoding**. [NeMo model initialization](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-transducer-nemo-model.cc#L208-L224) adds the last-index blank and detects TDT from the model URL containing `tdt`; joiner validation verifies the extra duration outputs. The observed v3 native debug output says `TDT model. vocab_size: 8193, num_durations: 5`, with 128-dimensional features, subsampling 8, and per-feature normalization.
- [NeMo recognizer construction](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-transducer-nemo-impl.h#L49-L68) passes `model_->IsTDT()` to **both** greedy and modified-beam decoders. Adding hotwords changes the search method, **not the model family or TDT flag**. Native outputs from all speech-bearing greedy/beam/hotword runs contain nonempty TDT durations; biased v2 silence has 421 durations for 421 tokens. The bundled ARM64 JNI library also contains both `OfflineTransducerGreedySearchNeMoDecoder` and `OfflineTransducerModifiedBeamSearchNeMoDecoder` type names. It was inspected, not executed on this host.
- The [greedy NeMo decoder](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-transducer-greedy-search-nemo-decoder.cc) explicitly splits token and duration logits, uses recurrent decoder state, honors duration skips, and caps zero-duration emissions. It is also the path in [official Parakeet examples](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html).
- Both bundled `.vocab` tables match the official model token pieces **exactly in order**: v2 has 1,024 pieces plus `<blk> 1024`; v3 has 8,192 pieces plus `<blk> 8192`. No tokenizer mismatch was found. The BPE table is needed for hotword encoding, not greedy acoustic recognition; native greedy uses `tokens.txt` and model metadata, without a BPE encoder.

## Concrete bias runaway mechanism

The old `SpeechEngine.initializeEngine()` selected modified beam search whenever **any** vocabulary entry existed. Its configured score was 2.0 by default and adjustable from 1.0 to 5.0. There was no unbiased reference decode, no acoustic acceptance gate, and no restriction on how often a completed term could earn a reward.

The [NeMo beam decoder](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-transducer-modified-beam-search-nemo-decoder.cc#L263-L270) adds `hotwords_score_` to matching token log-probabilities **before top-k**. Those modified values enter accumulated hypothesis scores. It then [calls `ForwardOneStep` and adds its context score again](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-transducer-modified-beam-search-nemo-decoder.cc#L350-L357). [ContextGraph](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/context-graph.cc#L69-L113) supplies a token reward along matching arcs and resets to root on completed non-strict matches. A term can therefore be rewarded repeatedly; finishing does not remove its accumulated reward. `Finalize` rolls back unfinished context-graph credit, not all of the independent pre-top-k bonus. The ten-symbol-per-frame cap prevents an infinite inner loop, **not hallucinated repetitions across frames**.

This is more specific than “TDT beam unsupported”: this pinned version implements it, but the exposed biased path demonstrably hallucinates. Lowering a slider or deduplicating resulting text is not a reliable repair. Greedy ignores contextual graphs and removes this reward path entirely.

## Full-recording findings and bounded finalization

The v1.0.6 change retained the full exact PCM (valuable and preserved here) but fed up to 120 seconds through one noninterruptible native call. Short VAD-segment decoding previously limited the amount of silence/context fed to the biased decoder. Removing those limits exposes the unsafe search to longer inputs; it does not itself make TDT an RNNT model.

Long inputs also affect otherwise unbiased decoding. The same v2 public utterance followed by silence out to 120 seconds gave greedy `We didn't wish to see it any more, observed Phoebe, turning away her eyes. It is certainly very like the old portrait.` in **23.732 s**, rather than the original opening `Well, I don't wish ...`. The encoder’s per-feature normalization is applied over each whole stream, so changing its silence/context changes input features. This is a plausible additional contributor, not a proven complete explanation of that wording change.

Finalization now:

1. Takes the exact original live PCM snapshot (or validated file PCM), independently of preview/VAD decisions.
2. Partitions it into contiguous, non-overlapping slices of **at most 20 seconds**. Searches for the minimum-energy 200 ms region in the last approximately five seconds and cuts at its midpoint, preferring seconds 15–20. A final tail is retained, with at least two seconds reserved when splitting a recording just over the cap. No sample is dropped, repeated, filtered as “nonspeech”, amplified, resampled on the Android 16 kHz path, or padded for final inference.
3. Creates/releases a fresh native stream per slice and decodes with `greedy_search`, `hotwordsFile=""`, `hotwordsScore=0`, `blankPenalty=0`, CPU/two threads, `nemo_transducer`. Joins these final results only; preview text is never a fallback. There is **no repetition detector, text deduplication or retry-with-cleaned-text path**.
4. Checks cancellation/service-enabled between calls and before delivery. The existing native mutex covers the entire operation and stream cleanup; native decode remains noninterruptible. No model is destroyed under active inference and no partly finalized transcript is published on cancellation/failure.

Twenty seconds is a conservative application resource/context bound, **not a claimed upstream model limit or guaranteed safe latency**. On continuous speech the forced boundary can split a word and lose cross-window context; quiet energy is not a perfect speech detector. No VAD filtering is used to avoid dropping quiet speech. Native memory remains additional to PCM buffers; native abort/OS death cannot be caught safely in Kotlin.

## Vocabulary behavior is deliberately limited and visible

Existing vocabulary entries are retained. Settings replaces the unsafe acoustic score slider with **Use custom vocabulary spelling**, enabled by default and snapshotted at session initialization. Both Settings and Vocabulary explain that **acoustic boosting is disabled** for these TDT models because it can cause repetition. This is a safety restriction on this decoder/version, not a blanket assertion that all TDT implementations lack contextual bias.

Optional orthographic hints may only match identical Unicode letters/digits/combining marks, ignoring case and spaces, over complete recognized word boundaries. They can recase/regroup `next cloud` to `Nextcloud`; they cannot change `traffic` into `Traefik`, insert unheard letters, delete repeated words, cross punctuation, or invent a missing term. Conflicting entries with the same normalized spelling are ignored. Terms containing punctuation are retained in the list but are not eligible for this conservative spelling pass. Disabling the switch returns unstyled native text. This is **not a replacement for acoustic phrase boosting** and is not advertised as such. Previews stay unstyled/unbiased.

Direct execution of the compiled production Kotlin helper produced:

```
Input:  Send next cloud to ludo. Loud, cloud, nextcloud nextcloud.
Hints:  Nextcloud, Ludo
Output: Send Nextcloud to Ludo. Loud, cloud, Nextcloud Nextcloud.
```

Real duplicate words remain duplicate. A future acoustic vocabulary feature needs a corrected/vetted native scorer and evaluation on speech, silence and near-homophones; simply reinstating beam with a small bonus is not justified by these samples.

## Native observations

Environment: Linux x86_64, pip `sherpa-onnx==1.13.8` / `sherpa-onnx-core==1.13.8`, CPU provider, two inference threads, 128-dimensional 16 kHz features, zero blank penalty, four beam paths where applicable. Every run completed normally with no decoder exception. v3 official WAV rates (24 kHz English / 22.05 kHz French) were passed honestly to sherpa's native resampler; Android inputs remain original 16 kHz PCM.

Public model/audio origins:

- [Official v2 int8 model and `test_wavs/0.wav`](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/tree/main)
- [Official v3 int8 model and English/French WAVs](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/main)

| Input | Decoder / bias | Native result | Decode time |
|---|---|---|---|
| v2 public speech, 7.435 s | greedy / none | A below | 1.991 s |
| same | beam / none | A | 1.127 s |
| same | beam / Nextcloud 2.0 | A | 1.221 s |
| silence, 12 s (v2) | greedy / none | empty | 1.665 s |
| same | beam / none | empty | 1.765 s |
| same | beam / Nextcloud 2.0 | 421 tokens; repeats Nextcloud (full text in JSON) | 2.251 s |
| v2 speech + silence to 20 s | greedy / none | B below | 2.763 s |
| same | beam / Nextcloud 2.0 | A | 3.637 s |
| silence, 120 s (v2) | greedy / none | empty | 22.875 s |
| same | beam / Nextcloud 2.0 | 538 tokens; repeats Nextcloud (full text in JSON) | 23.916 s |
| v2 speech + silence to 120 s | greedy / none, one stream | C below | 23.732 s |
| same | beam / Nextcloud 2.0, one stream | C | 23.794 s |
| same original PCM | greedy / production Kotlin partitions | A | 16.511 s summed native decode |
| v3 public English, 3.845083 s | greedy / none | D below | 0.577 s |
| v3 public French, 4.969070 s | greedy / none | E below | 0.710 s |
| same French | beam / Nextcloud 2.0 | E | 0.774 s |
| silence, 12 s (v3) | greedy / none | empty | 1.658 / 1.662 s (EN/FR-rate runs) |
| silence, 12 s (v3) | beam / Nextcloud 2.0 | empty | 1.743 s |
| v3 English + silence to 20 s | greedy / none | D | 2.769 s |
| v3 French + silence to 20 s | greedy / none | E | 2.784 s |
| same French | beam / Nextcloud 2.0 | E | 2.901 s |

Exact transcripts:

**A:** `Well, I don't wish to see it any more, observed Phebe, turning away her eyes. It is certainly very like the old portrait.`

**B:** `Well, I don't wish to see it any more, observed Phoebe, turning away her eyes. It is certainly very like the old portrait`

**C:** `We didn't wish to see it any more, observed Phoebe, turning away her eyes. It is certainly very like the old portrait.`

**D:** `Ask not what your country can do for you, ask what you can do for your country.`

**E:** `Ne vous demandez pas ce que votre pays peut faire pour vous. Demandez-vous plutôt ce que vous pouvez faire pour lui.`

The bounded 120-second exercise used boundaries emitted by the **compiled production Kotlin `RecognitionPolicy`**, not a separately guessed Python algorithm: `[0,318400)`, `[318400,636800)`, `[636800,955200)`, `[955200,1273600)`, `[1273600,1592000)`, `[1592000,1886400)`, `[1886400,1920000)`. Thus all 1,920,000 source samples were supplied once, at lengths 19.9, 19.9, 19.9, 19.9, 19.9, 18.4 and 2.1 seconds. Later silence-only slices returned empty text; the first retained the original utterance. This small public corpus is evidence of the fix mechanism, not a general word-error-rate benchmark. The v3 hotword probe did not reproduce the v2 silence failure; do not generalize its absence to all terms/audio.

## Reproduction and verification artifacts

One-off native diagnostic programs, downloaded public model/WAV files, Python venv, full JSONL stdout/stderr, compiled-Kotlin helper runner and build log are retained locally under `app/build/asr-diagnostics/` (ignored build output; **not packaged in the APK**). For example, from the repository root:

```sh
python3 -m venv app/build/asr-diagnostics/venv
app/build/asr-diagnostics/venv/bin/pip install sherpa-onnx==1.13.8 numpy
app/build/asr-diagnostics/venv/bin/python app/build/asr-diagnostics/native_probe.py
app/build/asr-diagnostics/venv/bin/python app/build/asr-diagnostics/native_probe.py --method modified_beam_search --hotword Nextcloud --score 2
app/build/asr-diagnostics/venv/bin/python app/build/asr-diagnostics/native_probe.py --model v3 --wav fr.wav
```

`native_probe.py` creates recognizers with the exact configurations serialized in the evidence JSON, reads official PCM16 WAVs with `wave`, scales signed samples by 32768, and separately supplies unmodified public speech, digital silence and speech followed by silence. These local scratch scripts disappear on a clean build-directory removal; durable observed evidence/configurations are in this document and the JSON, not a claim of a checked-in test suite.

- `ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug lintDebug --console=plain`: **BUILD SUCCESSFUL**. Final run: 13 s; lint **0 errors, 63 warnings**. No unit tests created or run; lint's existing test-source analysis is not test execution.
- `aapt dump badging`: package `com.ludo.voicedictation`, **versionName 1.0.7 / versionCode 8**, ARM64/ARMv7.
- APK: `app/build/outputs/apk/debug/app-debug.apk`; SHA-256 `2560b9095af36203af1324696b95177629120ab8dd84bc7ffe680b41092ac228`.
- Streaming preview capture/ring buffering, exact PCM snapshot, file regular-FD validation, command 4’s **no-microphone** branch, native mutex/release ordering, cancellation suppression, signature/UID/Binder-owner gates, overlay geometry and notification-shade lifecycle code are preserved. IPC protocol 1 and the 120-second PCM contract do not change.
- `adb devices` returned no connected device. Android microphone/native ABI behavior, real notification/shade/lock gestures, phone/watch transfer, Binder lifetime/security rejection and actual on-device latency/memory still require device verification. Linux evidence is not substituted for that work.
- No commits, pushes or publication.
