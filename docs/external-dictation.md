# External dictation IPC — protocol 1

Live microphone commands are available in Voice Dictation 1.0.4 (versionCode 5).
File transcription is added in **1.0.5 (versionCode 6)** without changing protocol 1.
**1.0.7 (versionCode 8)** uses unbiased TDT greedy final decoding in windows of at
most 20 seconds for both live capture and files, superseding 1.0.6’s single
full-recording native call. All original PCM is retained; no VAD speech filtering.
Recording remains capped at 120 seconds; the total IPC deadline is ten minutes.
Vocabulary is optional spelling-only; unsafe native acoustic bias is disabled.
See [diagnosis and native verification](asr-regression-1.0.7.md).

## Setup and security

- Install Voice Dictation (`com.ludo.voicedictation`) and Hermes
  (`com.hermes.assistant`) signed with the **same signing certificate**. A mismatched
  debug/release signing key is rejected, not bypassed.
- Hermes must request
  `<uses-permission android:name="com.ludo.voicedictation.permission.CONTROL_DICTATION" />`.
  Voice Dictation defines that permission with `protectionLevel="signature"` and
  requires it on the exported service. Request/reinstall as necessary if Android
  has not granted the custom permission. Hermes should declare a package query for
  `com.ludo.voicedictation` when discovering availability.
- Every message independently checks Android's `Message.sendingUid`: its **only**
  package must be exactly `com.hermes.assistant`, its signature must match Voice
  Dictation's UID signature, and it must hold `CONTROL_DICTATION`. Shared UIDs,
  same-key apps with other package names, missing callbacks and unauthorized callers
  are rejected without operating the microphone. Caller identity in the Bundle is
  never trusted.
- Enable **Enable dictation service** in Voice Dictation Settings and download the
  selected ASR model (including VAD). Live START additionally requires microphone
  runtime permission in both apps. File transcription requires **no microphone
  permission** in either phone app and never opens the phone microphone.
  Accessibility and overlay permissions are **not** required for either IPC path.
- Bind explicitly to `ComponentName("com.ludo.voicedictation",
  "com.ludo.voicedictation.integration.DictationControlService")` from a visible
  Hermes activity. Use `Context.BIND_AUTO_CREATE`, plus
  `Context.BIND_INCLUDE_CAPABILITIES` on API 30+ for live START so the visible client's microphone
  capability is propagated to the bound service. This path does not start a
  foreground service. Hermes must CANCEL and unbind when it backgrounds; permission
  denial or microphone restrictions are reported as ERROR, never replaced by cloud ASR.

No public broadcast, PendingIntent or arbitrary result intent is used. Results go
only to the supplied Messenger. No external transcription is injected through
accessibility, copied to the clipboard, or sent to the ordinary overlay callback.
Successful external transcripts are included in Voice Dictation's local history.

## Commands

Wrap `onServiceConnected`'s IBinder in `Messenger`. All commands must set `replyTo`
to the **same callback Messenger** for the session and put these values in `data`:

| Bundle key | Type | Required value |
| --- | --- | --- |
| `session_id` | String | New canonical UUID string per session; preserve it exactly on STOP/CANCEL |
| `protocol_version` | Int | `1` |

| `Message.what` | Name | Behavior |
| --- | --- | --- |
| `1` | START | Claims exclusive engine ownership and begins loading/capture. |
| `2` | STOP | Stops microphone capture and finishes bounded unbiased decoding over all captured PCM; previews are not final. |
| `3` | CANCEL | Stops capture and discards all pending transcription; no acknowledgement or final result. |
| `4` | CMD_TRANSCRIBE_FILE | Claims exclusive ownership and transcribes a completed PCM file asynchronously; no START or STOP is needed. |

START and CMD_TRANSCRIBE_FILE reject busy sessions, including each other and
ordinary floating dictation; they never preempt them. STOP and CANCEL must match the original sending UID, exact
`session_id`, and callback **Binder identity**. Mismatched commands return ERROR
and do not alter the existing owner. Unknown commands return ERROR. For malformed
version/UUID commands, ERROR echoes the supplied session ID (or an empty string
if absent); no session is created. Commands missing `replyTo` are dropped.

Do not treat STOP as a synchronous result or submit the last partial. STOP during
loading yields an empty-speech error if no microphone capture began. Repeated
STOP is safe while processing. Commands after completion are not a new session.
Use a new UUID and a new START when starting again.

### Completed file transcription (watch → Hermes phone → Voice Dictation)

Send `Message.what = 4` (`CMD_TRANSCRIBE_FILE`) with the same required
`session_id`, `protocol_version = 1` and `replyTo`, plus these **mandatory** fields:

| Bundle key | Type | Required value |
| --- | --- | --- |
| `audio_fd` | `ParcelFileDescriptor` | Readable descriptor for a completed regular file |
| `sample_rate` | Int | `16000` |
| `channels` | Int | `1` |
| `encoding` | String | `pcm_s16le` |

The file must contain **raw signed 16-bit little-endian mono PCM at 16 kHz**,
without a WAV/container header. Its size must be nonzero, even, and at most
**3,840,000 bytes (120 seconds)**. Pipes, sockets, devices, empty/odd/oversized
files and unreadable descriptors produce ERROR. No URI or path is accepted or
opened by the service. Hermes must finish writing before sending and must not
modify the file while it is being read; truncation/size changes are rejected.
The service reads from byte offset zero independently of the sender's seek offset.

For example, after binding with `Context.BIND_AUTO_CREATE` and creating a callback
Messenger (which must remain alive for the session):

```kotlin
val sessionId = UUID.randomUUID().toString()
ParcelFileDescriptor.open(completedPcmFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
    serviceMessenger.send(Message.obtain(null, 4).apply {
        replyTo = callbackMessenger
        data = Bundle().apply {
            putString("session_id", sessionId)
            putInt("protocol_version", 1)
            putParcelable("audio_fd", fd)
            putInt("sample_rate", 16000)
            putInt("channels", 1)
            putString("encoding", "pcm_s16le")
        }
    })
} // Messenger transfers a duplicate: Hermes closes its own descriptor after send.
```

The receiver closes its descriptor on every path, including identity/metadata
rejection, busy rejection, missing callback, I/O failure, cancellation and success.
Size validation and bounded I/O run off the main thread. The descriptor closes
as soon as reading finishes (before model loading/inference), or promptly on
cancellation. The file uses **one full-context final decode**, preserving native
punctuation/capitalization; it does not repeatedly decode growing prefixes or
emit file PARTIAL events. A valid file flows `loading → processing → RESULT/ERROR`,
with the same event envelopes as live dictation. STOP for an owned file session
is a safe no-op: it does not abort loading or finalization. CANCEL still requires
the original UID, session ID and callback Binder and suppresses all later output.
Use a fresh UUID and command 4 for each new file; do not precede it with START.

## Events

All events are `Message`s sent to `replyTo`, with `data.session_id` (String) and
`data.protocol_version` (Int, always `1`). Hermes must filter both fields before
updating UI or submitting a prompt; callbacks from cancelled/stale sessions must
be ignored.

| `Message.what` | Name | Additional Bundle key | Meaning |
| --- | --- | --- | --- |
| `101` | STATE | `state`: String | `loading`, `recording`, or `processing` |
| `102` | PARTIAL | `text`: String | Replace the displayed draft (up to the latest 12 seconds); never append or submit it. |
| `103` | RESULT | `text`: String | Nonempty final transcript after final decode and complete engine cleanup. |
| `104` | ERROR | `error`: String | Human-readable failure, validation error, busy rejection, or timeout. |

Typical flow: START → loading → recording → partials → STOP → processing →
RESULT (or ERROR). Processing may be repeated; there is no mandatory idle event.
Some short sessions have no partials. Live previews are throttled (at least two
seconds between decodes), may disappear after a recoverable preview failure, and
may differ substantially from the final. They contain only a rolling window, not
an authoritative full-session transcript. Only RESULT may be submitted. Live PCM,
including pauses and sub-VAD-window remainders, is retained up to **120 seconds**;
reaching the cap automatically stops capture and starts finalization without STOP.
A final decode error produces ERROR, never the last partial or stitched text.
An empty final transcript is ERROR, not an empty RESULT. Recognition accuracy,
including silence/noise behavior, is model-dependent and not guaranteed.
Failures of a running session terminate it; validation errors and busy rejections
do not cancel the existing session. A maximum **ten minutes from accepted START
or CMD_TRANSCRIBE_FILE** applies to loading, recording and processing together.
Timeout emits ERROR, stops capture and discards pending output.

Clients should allow **five minutes after STOP** for authoritative final processing
(and handle automatic processing at the recording cap). This is a recommended
client deadline, not a performance guarantee; slower devices can still time out. Do not submit a draft on timeout. Genuine activity
backgrounding, unbind and explicit cancellation must still cancel promptly.

Hermes should focus its text composer and START on the first power-long-press;
on the next press, STOP and wait for RESULT, then submit that final text once.
It can unbind immediately on RESULT or a terminal session ERROR: success is not
emitted before decoding and cleanup finish. For explicit cancellation, send
CANCEL with `replyTo` set, clear the client session locally, and unbind without
waiting for an acknowledgement.

## Lifecycle and overlay coexistence

The service links the callback Binder to death. Callback failure/death, last
unbind or service destruction cancels capture and suppresses subsequent output.
Cancellation cannot interrupt a native decode already executing; microphone
capture stops immediately and a pending file descriptor closes promptly. Engine ownership stays reserved until the async
job and native cleanup finish. A new START during that drain returns busy.
The same reservation applies after timeout. The service never cancels the job
in a way that would release native objects underneath active inference.

External sessions have their own owner token and listener. Overlay hide/destroy,
accessibility focus changes and overlay taps cannot stop or hijack them. Ordinary
floating dictation inserts only its own authoritative final result into the
active focused field. Transient focus loss such as opening notifications no longer
cancels an active floating session. Screen-off/lock, explicit cancellation,
accessibility interruption/destruction and disabling still cancel local capture;
these overlay events never hijack an IPC-owned session.
Disabling dictation or explicitly releasing models from Voice Dictation Settings
cancels an active external session and reports ERROR when the client is connected.

## Verification scope

`./gradlew assembleDebug lintDebug` builds the APK and runs Android static checks.
No unit tests are created or run for this change. Device-side binding/signature
rejection, visible-client microphone capability propagation, local-model decoding,
Binder death, file descriptor validation/closure/cancellation, watch PCM decoding,
microphone-permission-free file operation, focus changes and power-button flow require verification on an
Android device with both applications installed and identically signed.
