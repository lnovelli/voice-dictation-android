# Voice Dictation development

Inspect the current worktree, code, manifest and existing documentation before changes; preserve unrelated work. Prefer security, then correctness, then resource efficiency. Never put secrets in the APK or log private audio, transcripts or credentials.

Read the focused notes applicable to your change:
- [Architecture and IPC](docs/development/architecture-ipc.md): ownership, exported service and Hermes integration. The detailed wire contract is [external dictation](docs/external-dictation.md).
- [Overlay and privacy](docs/development/overlay-privacy.md): accessibility focus, local insertion, diagnostics and gestures.
- [Audio pipeline](docs/development/audio-pipeline.md): capture, VAD, TDT, streaming and cancellation.
- [Models and ASR validation](docs/development/models-asr-validation.md): archive safety, native inference and evidence.
- [Text and History](docs/development/text-history.md): final transforms, remote polishing, consent and in-place reprocessing.
- [Build and release](docs/development/build-release.md): focused checks, device-test limits, signing and publication.

Existing release-era documents are historical where labeled; check current source before applying version-specific statements. A build or host probe is not Android device validation. By default run focused compile/static checks, not unit tests, lint, emulator or device tests; conduct on-device behavioral testing only when explicitly requested. Report what remains unverified.
