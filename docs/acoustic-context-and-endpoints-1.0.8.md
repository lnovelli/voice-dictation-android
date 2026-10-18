# Native acoustic context and final endpoints — 1.0.8 / code 9

## Release outcome

- **Acoustic custom-word recognition remains blocked, not restored.** It was exercised in actual native recognition, not replaced with spelling/case rules. Do not describe the optional spelling-only switch as fulfillment of acoustic boosting.
- Added a **final-only lossless endpoint mitigation** in `FinalAudioPolicy.kt`, used by both microphone and completed-file finalization. It removes only exact-zero leading/trailing PCM, retaining up to **500 ms of original audio** on either side of every nonzero sample. All internal pauses and every nonzero sample remain in one contiguous interval. No streaming, preview, VAD, windowing, stitching, waveform amplification, synthetic padding or second ASR decode was added.
- This repairs the reproduced v3 English/French **120-second zero-padding failure**. It does **not** repair arbitrary noisy tails or long internal pauses. The v3 model description and empty-transcript error now disclose that limitation. Publication/on-device acceptance remains the parent workflow; this investigation made no commit/push/publication.

## Acoustic context: real evidence and reason for blocking

The ignored diagnostic `app/build/asr-diagnostics/acoustic-bias-research/` contains **303 completed native acoustic-bias decodes** (191 v2, 112 v3), verified by `summarize.py`, with exact configurations, all native text/tokens/timestamps/durations/emitted-token scores, model hashes and raw stderr. These are benchmark executions on a small public/synthetic corpus, not WER statistics. Public official human recordings were used as negative controls; no private user audio or vocabulary was read. Additional target/near-target prompts were explicitly synthetic FFmpeg/libflite `slt` speech.

Compared greedy; unbiased four-path beam; four-path scores 0.1/0.25/0.5/1/2; exactly-one-path scores 0.25/0.5/1; lowercase terms; global score zero with explicit per-phrase graph reward. Also exercised the real per-stream API with the same recognizer and PCM, `create_stream()` versus `create_stream("Traefik/Nextcloud/...")`.

### Actual positive acoustic change

Synthetic pronunciation proxy (generator input, not a human-word accuracy reference):

```
Please open tray feek and grah fah nah. Check zah bix and im itch.
```

Pinned 1.13.8/v2 int8, `modified_beam_search`, exactly one path, score 1, zero blank penalty, matching BPE vocabulary, same recognizer/PCM:

```
OFF: Please open Trafik and Grafan not check the box and image.
 ON: Please open Trafik and Grafana, check the box and Image.
```

This changes acoustic token selection and segmentation (`Grafan not` → `Grafana,`), with **no text correction**. Source: `v2-stream.stdout` / `v2-stream-pairs-score1.0.jsonl`. The straightforward written technology-word prompt remained substantially wrong; **Traefik was not successfully restored** in these selected examples. Do not claim arbitrary-word accuracy from this isolated improvement.

### Silence and negative controls

Sole hotword `Nextcloud`, v2:

| Mode | 12-second silence tokens | 120-second silence tokens |
|---|---:|---:|
| Greedy / no vocabulary | 0 | 0 |
| Four paths, score 2 | **421** | **538** |
| One path, score 0.5 | 0 | 0 |
| One path, score 1 | 0 | 0 |

Source: `v2-nextcloud120.stdout`. There were 58 silence executions across the bias investigation; only the two old-configuration cases above were nonempty. V3 did not reproduce the silence runaway. Merely adding two other terms to the v2 score-2 dictionary made the 12-second silence result empty, so success is dictionary-dependent and cannot certify arbitrary terms.

The negative synthetic phrase `Please restart traffic and send the backup to the next crowd for Lucy.` stays unchanged in paired one-path off/on runs on both models; official public negative speech also stays unchanged. A common-word dictionary leaves 12-second low-level seeded white noise, tone and silence empty with one-path score 0.5/1. These controls are encouraging but not sufficient.

### Decisive regression

V3, same 40-second PCM: synthetic target phrase, **20-second internal pause**, then `The weather is pleasant today. Please close the window and bring me a glass of water.`:

```
Greedy:
Please open Trafik and Grafana, check the X and IH. The weather is pleasant today, please close the window and bring me a glass of water.

One path, score 1, custom words:
Please open Trifeek and Grafana, check the X and IH. Please close the window and bring me a glass of water.
```

The unrelated weather sentence disappears (`v3-tech.stdout`). Score 0.5 preserved that wording but had weak/no desired lexical benefit in selected target samples. Score 1 also changes v3 `Partainer ... Cabernetes` to `Partaner ... Kabernates` on a written prompt for `Portainer ... Kubernetes`, not a proven correction. Therefore no native bias configuration was enabled in production.

### Exact API/upstream findings

TDT duration decoding **is implemented** for both greedy and NeMo beam paths. The failure is not evidence that TDT is unsupported. Pinned NeMo beam adds `hotwords_score_` to token log probabilities before top-k and accumulates that modified value, then adds ContextGraph reward as well. Completed terms can earn reward repeatedly; finalization does not roll back the independent pre-top-k bonus. Greedy avoids that path.

Exactly one active path removes competition between accumulated histories, leaving a bounded local token bonus: score `s` can only select a boosted token whose raw conditional probability is at least `exp(-s)` times the raw best token at that history/frame. That is a useful restriction, **not a calibrated acoustic confidence or a safety guarantee**. Its v3 regression above prevents recommending it as a safe restoration. Do not restore four paths or the old adjustable score.

Biased Python `ys_log_probs` already include the bonus and can be positive. They are not clean acoustic probabilities. The actual AAR's Kotlin API exposes no raw token scores, N-best, comparable raw joint path score or rescoring/forced-alignment API (`aar-api.javap.txt`). A defensible acoustic acceptance/rescoring gate is not available as a Kotlin flag. No separate CTC model/backend was validated; these TDT model files cannot be reinterpreted as CTC.

At fetched upstream master `1a9d234ab95567eba8c2e89873a87d70b79633e2`, the relevant decoder file is byte-identical to 1.13.8. PR [3657](https://github.com/k2-fsa/sherpa-onnx/pull/3657) was open/unmerged; its changes do not remove double/unbounded context reward. The pinned code already has the blank `std::max(1, predicted_skip)` guard discussed by that PR. Neither an unverified upgrade nor that PR is an established fix.

A future native repair should separate raw token scores from top-k-only bonuses, avoid double addition, bound total phrase credit and expose genuine acoustic scoring if accepting biased alternatives. It needs its own implementation and referenced human target/negative/long-pause evaluation. No patched native binary is claimed here.

## Final endpoint mitigation: exact production behavior

`FinalAudioPolicy.endpointRange(samples, sampleRate)` finds the first and last `samples[i] != 0f`, retains `sampleRate / 2` original samples around them, clamped to the original array. An all-zero recording stays unchanged; this is not speech detection. `SpeechEngine.decodeRecording()` calls this **once after Stop** (or completed-file read), copies at most that single interval, then invokes the existing single native decode. Original capture and 120-second ownership/cap/cancellation semantics are unchanged. The native mutex remains held through finalization; cancellation is checked before/after trimming and before delivery.

Exact-zero trimming was chosen deliberately. A fixed 20 ms RMS gate of 0.0005 also recovered low-noise tails but removed quiet spoken endpoint samples in a controlled low-amplitude public-speech case. It was **not shipped**. Quiet/noisy nonzero PCM is retained even if the model subsequently fails. No meaningful phoneme can be dropped by the shipped selection rule, although retaining its samples does not guarantee the model recognizes it.

### Native execution and compiled-production verification

`endpoint_probe.py` completed **45 native full-pass executions** across v2 English, v3 English and v3 French. Its 72 strategy result rows explicitly mark identical-PCM reuse, rather than pretending duplicate rows were fresh inferences. Inputs were official WAVs converted to exact mono 16 kHz PCM16, then full-120-second constructions: trailing zeros, leading/trailing zeros, fixed-seed stationary noise RMS approximately 0.0002 or 0.002, constructed continuous repeated public speech, silence, and a separately labelled scaled-float quiet-edge control.

The **compiled production Kotlin helper** was run directly by `EndpointProbe.java`, not a unit-test framework. Its ranges matched all **24** endpoint cases; the diagnostic verifies every removed sample is zero and hashes the exact retained PCM against actual native inputs. Six additional native decodes replayed those compiled-production ranges, preserved in `production-native-replay.jsonl`.

| Original input | Untrimmed v3 | Compiled-production range / native result |
|---|---|---|
| English speech then zeros to 120 s | Empty | `[0,69522)`; full `Ask not what your country can do for you, ask what you can do for your country.` |
| French speech then zeros to 120 s | Empty | `[0,87506)`; full French sentence, with punctuation/case variation versus short baseline |
| English speech centered at 60 s in 120 s zeros | Empty | `[952001,1029522)`; full English sentence |
| French speech centered at 60 s in 120 s zeros | Empty | `[952000,1047506)`; full French sentence |
| v2 public speech, trailing/both-side zeros | Nonempty, changed opening | Both production ranges recover original short-clip wording |

Exact French recovered text:

```
Ne vous demandez pas ce que votre pays peut faire pour vous, demandez-vous plutôt ce que vous pouvez faire pour lui.
```

### Remaining limitation is real and visible

- V3 English/French speech followed by stationary low-level noise to 120 seconds still returns **empty** at both tested noise levels. Exact-zero trimming correctly leaves that nonzero PCM untouched. There is no claim that this fixes actual microphone noise or all 120-second recordings.
- Constructed continuous public speech (repeated official clips with 100 ms joins, **not** a genuine 120-second human continuous recording) remains all **1,920,000 samples**, bit-identical, and decodes nonempty: v2 800 tokens, v3 EN 822, v3 FR 833. The trimmer makes no change to its input or the already-executed native result. This establishes preservation and nonempty decoding on that construction, not general long-form WER.
- All-zero 120-second PCM remains unchanged and returns empty on both models.
- Internal pauses are retained, including the previously observed v3 losses over 20-/90-second gaps. Quiet-edge words are never removed from PCM by the production rule, but v3 recognition can still fail/hallucinate on such long-context controls.

The model description warns about long internal pauses and quiet/noisy padding; empty-transcript errors no longer imply the input necessarily contained no speech. Shorter recordings are recommended. These limitations remain relevant to release acceptance; the user-requested broader safe acoustic vocabulary restoration is unfinished.

## Files and commands

Tracked changes in this follow-up: `asr/FinalAudioPolicy.kt`, finalization/error wording in `asr/SpeechEngine.kt`, capability/model-limit descriptions in `res/values/strings.xml`, this document and updates to `record-then-transcribe-1.0.8.md`. No changes to model family, acoustic bias settings, capture gestures, filler filtering, IPC/security/ownership or version numbers were made here.

All full raw/native artifacts are ignored under `app/build/asr-diagnostics/acoustic-bias-research/`, notably `REPORT.md`, `summary.json`, `model-sha256.json`, `v{2,3}-*.stdout`/stderr/JSONL, `endpoint_probe.py`, `production-endpoints.tsv`, `production-endpoint-validation.json`, `production-native-replay.jsonl` and build logs. No private samples were used. The first technology-list synthetic generation failed on inline FFmpeg comma parsing; switching to a text file fixed it and the failed stderr is retained.

From `/root/VoiceDictation`:

```sh
PY=app/build/asr-diagnostics/venv/bin/python
D=app/build/asr-diagnostics/acoustic-bias-research
$PY "$D/stream_probe.py" --model v2
$PY "$D/stream_probe.py" --model v3
$PY "$D/bias_probe.py" --vocab nextcloud --configs greedy,b2,one05,one1 --cases silence12,silence120,paused120
$PY "$D/endpoint_probe.py" --model v3 --wav en.wav
$PY "$D/endpoint_probe.py" --model v3 --wav fr.wav
$PY "$D/endpoint_probe.py" --model v2 --wav 0.wav
$PY "$D/production_endpoint_replay.py"  # after Java EndpointProbe emits production-endpoints.tsv
ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug lintDebug --console=plain
```

Native observations are Linux CPU results, with some independent probes running concurrently, not controlled Android latency measurements. No unit tests were created/run and no Android device was connected/provided for acceptance. Build/lint verifies packaging/static checks, not microphone, gesture, Binder lifecycle or word accuracy.

Final verification: `assembleDebug lintDebug` **BUILD SUCCESSFUL** (25 s), lint **0 errors / 63 warnings**, and `git diff --check` clean. Debug APK metadata is `com.ludo.voicedictation`, **1.0.8 / code 9**; SHA-256 `cbcc2f5ab17b3a6a9b6234d603351ba10432746de97f2609a09a24db694be7b4`. Final log: `app/build/asr-diagnostics/acoustic-bias-research/build-endpoints-final.log`. This is a local debug artifact, not a publication claim.
