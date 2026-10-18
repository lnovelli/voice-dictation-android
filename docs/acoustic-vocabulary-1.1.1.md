# Experimental acoustic vocabulary — v1.1.1 / versionCode 12

## Vocabulary-tab controls

Acoustic hotword bias remains **off by default**. When enabled with at least one eligible saved term, the app uses sherpa-onnx `modified_beam_search` and the selected Parakeet model's BPE vocabulary.

- Max active paths: integer 2–8, default 4.
- Hotword score: float 1.0–5.0, default 1.0, slider step 0.1.
- Blank penalty: fixed at 0.0; there is no slider.
- Saved settings apply on the next transcription; no app restart is needed. Settings are snapshotted when the recognizer is initialized after capture stops. A change made after decoding has started applies to the following transcription.

The hotword score controls the decoder's bias magnitude; active paths controls beam width and does not itself strengthen the vocabulary. Higher bias can affect unrelated words or hallucinate terms. Raise it only for controlled comparisons on the same audio, and review all output.

Acoustic terms are limited to letters, combining marks, digits, spaces, apostrophes and hyphens (maximum 80 characters); other saved terms remain available to the separate spelling-hint feature. Full probe observations and raw Linux artifacts are in [the v1.1.0 investigation](acoustic-vocabulary-1.1.0.md). Those tests were synthetic/public Linux runs, not Android-device acceptance, and higher hotword scores showed silence hallucination in some configurations. The feature remains experimental.
