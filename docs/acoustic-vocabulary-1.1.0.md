# Experimental acoustic vocabulary — v1.1.0 / versionCode 11

## Controls and behavior

The Vocabulary tab includes an **Enable acoustic hotword bias** switch and three sliders. Bias is **off by default** so an existing install keeps the prior unbiased greedy behavior unless the user opts in.

- Decoder: `modified_beam_search` only when the switch is on and at least one eligible saved term exists; otherwise `greedy_search`.
- Max active paths: 2–8; default 4.
- Hotwords score: 0.5–3.0; default 1.0.
- Blank penalty: 0.0–2.0; default 0.0. Leave at zero unless the audio is completely clean.
- Modeling unit: `bpe`; the matching bundled `parakeet_v2.vocab` or `parakeet_v3.vocab` is materialized in the app cache and passed by absolute path.
- The current vocabulary is written to an app-private cache hotwords file. Acoustic entries are restricted to letters, combining marks, digits, spaces, apostrophes and hyphens (maximum 80 characters); unsafe saved spellings are ignored for acoustic decoding, but remain available to the separate spelling-hint feature.

The recognizer cache key includes model, enabled decoder mode, hotword list and all active numeric settings. A changed configuration causes the old recognizer to be released and rebuilt under the existing native mutex. Capture, final-only processing, cancellation, external IPC ownership and the one-full-recording/one-decode behavior are unchanged.

## Native probe evidence for the requested defaults

The exact initial bias parameters were exercised on Linux with sherpa-onnx 1.13.8, the public v2/v3 int8 model files, matching BPE vocabularies, `modified_beam_search`, four paths, score 1.0, and blank penalty 0.0. The selected speech was generated with FFmpeg/libflite synthetic speech; it is not human speech and these examples are not an accuracy benchmark.

- On a 3.84-second phonetic technology-word clip, v2 greedy produced `Please open Trafik and Grafan not check the box and image.`; biased decoding produced `Please open Traefik and Grafana, check the box and Image.`
- On that same clip, v3 greedy produced `Please open Trafik and Graphana, check the Bix and IMH.`; biased decoding produced `Please open Trafik and Graphana, check Zabbix and Image.`
- On a selected 12-second digital-silence input, both models returned empty text for both greedy and biased runs with the tested multi-term technology vocabulary.
- The negative synthetic sentence already yielded `clubs` instead of `clouds` in unbiased decoding; the biased run did not correct it. On this one clip the changes were mainly punctuation. This does not establish behavior for other vocabularies or speakers.

Full raw paired results are in the ignored diagnostics directory under `app/build/asr-diagnostics/acoustic-bias-research/`, e.g. `v2-tech-greedy_b1-tech_phonetic_tech_negative_silence12.jsonl` and the corresponding `v3-...` file. Prior probes remain a material warning: with a single `Nextcloud` hotword, score 2.0 and four paths produced 421 tokens on 12 seconds of v2 digital silence; the pinned beam implementation adds hotword reward into path selection and accumulated context scores. Other tests showed dictionary- and long-pause-dependent speech changes. The enabled option is therefore experimental, can hallucinate or alter unrelated content, and is not recommended as a generally safe accuracy improvement.

## Verification limits

Android debug assembly and lint passed. Native evidence above is from the Linux sherpa-onnx runtime and synthetic/public diagnostic audio, not the packaged Android JNI runtime on a phone. No physical-device microphone/UI/latency or recognition acceptance was performed. Review every biased transcript; use the toggle to compare against greedy with the same audio.
