# Pause-only local boundary candidate

Status: benchmark-only implementation; not selected for ordinary dictation.
Production remains on its existing forced-boundary policy. This candidate
changes audio scheduling, not recognition weights, prompts, or formatting.

## Problem being tested

The existing pipeline cuts continuous speech at 15 seconds, retains 400 ms of
overlap for the next chunk, and permanently appends the first hypothesis.
The JNI wrapper then drops individual tokens from the overlap using estimated
timestamp midpoints. It cannot revise an invented first-chunk suffix. Repeated
words can survive in both hypotheses, while inaccurate timestamps can drop
needed subword pieces. This is not equivalent to exact word-boundary alignment.

The observed emulator `mau ngomong` error already existed in the first chunk;
changing overlap deletion alone could not repair it. Missing English in a tail
was not conclusively attributed to filtering versus recognition itself.

## Candidate behavior

In [LocalPipeline.kt](../android/app/src/main/kotlin/ai/voiceflow/voiceflow_mobile/LocalPipeline.kt),
`benchmarkPauseOnly` defaults to `false`. Enabling it requires explicit
`maximumChunkMs: 600000` and `overlapMs: 0`. The session entry point rejects the
candidate unless both debug and isolated-benchmark build flags are enabled.
The benchmark adapter owns JSON parsing and effective strategy metadata.

When enabled:

- A chunk is emitted only at an energy-detected pause after the configured
  minimum duration, or when recording stops. There is no application-enforced
  15- or 30-second boundary.
- Continuous audio longer than 30 seconds remains one native input. Whisper
  handles its own internal windows. There is no application overlap, token
  dropping, fuzzy text matching, or text-based deletion of repetitions.
- Audio remains on disk while recording. The work queue contains offsets, not
  complete audio arrays. Recognition remains serialized.
- The whole benchmark session is limited to 600 seconds. The limit counts
  original samples across pauses, rather than resetting per chunk. Exceeding it
  raises a sticky error; subsequently calling `finish()` cannot return a partial
  transcript as success.
- Exactly 600 seconds is supported, including uninterrupted speech. The final
  chunk allocation is independently bounded before narrowing its sample count.

This does not fail ordinary continuous speech at 30 seconds. If speech has no
suitable pause, processing waits until stop; that can increase post-stop delay.
The owner's 15-second budget applies to finished output for a 20-second
recording, not an asserted latency bound for a ten-minute recording.

## Why the native API accepts longer input

Inspected Whisper revision:
`080bbbe85230f624f0b52127f1ae1218247989f9`, with both existing source fixes.
Combined `src/whisper.cpp` SHA-256:
`3fdd9feb15ecd3a16b4deef320e0cf6a7dc200f07952b4f2f5c813fef9fcea79`.
The inspected local source is under
`build/.cxx/RelWithDebInfo/5p1x2d5c/arm64-v8a/_deps/whispercpp-src/`.

In that patched `src/whisper.cpp`:

- `whisper_full_with_state` computes the spectrogram from all supplied samples
  at lines 6831–6835; it does not truncate input at 30 seconds.
- Lines 6873–6874 derive the end from the full spectrogram duration when no
  duration override is supplied.
- Lines 7026–7048 loop over encoder windows at `seek`; line 7605 takes the
  selected decoder's `seek_delta`, and line 7770 advances the audio window.
- The model's audio-context cap limits each encoder window, not the total
  `n_samples` accepted by `whisper_full`.

The existing [JNI wrapper](../android/app/src/main/cpp/whisper_jni.c) already
passes the complete array length. With zero leading overlap it leaves token
timestamps off and returns full segment text. No JNI signature change is needed
for this candidate.

## Limits and validation still needed

Acoustic pauses are not semantic sentence or word boundaries. A higher energy
threshold may label quiet speech as a pause; the separate conservative speech
floor still keeps those samples eligible, but accuracy must be measured.

Whisper's internal timestamp-based advancement can still repeat, omit, or
hallucinate words. The inspected implementation stops when at most 100 ms
remain, and can clear earlier prompt context near the end of a long input.
Automatic language detection runs once per native call. Longer mixed-language
chunks therefore need real recognition checks, not just sample-coverage tests.

A ten-minute 16 kHz float array occupies 38.4 MB, and the temporary PCM16 byte
array occupies 19.2 MB. Those are not peak-memory estimates: JNI may copy the
float array, and native padded samples, spectrograms, model weights, and inference
buffers add memory. Peak process memory, cancellation responsiveness, sustained
speed, and heat require device measurements.

The new [LocalPauseOnlyChunkerTest.kt](../android/app/src/test/kotlin/ai/voiceflow/voiceflow_mobile/LocalPauseOnlyChunkerTest.kt)
contains twelve pure regression methods covering unchanged production defaults,
strict candidate configuration/runtime eligibility, no forced 20-second cut,
below/exact/above 30-second input, pause boundaries after 30 seconds, exact-stop
behavior, quiet/repeated speech eligibility, silence coverage, ingress-block
invariance, continuous ten-minute input, and a sticky cumulative session limit.
These establish scheduling and sample accounting, not word fidelity. Their
execution and the subsequent native/device checks are tracked by the integrating
agent; no phone acceptance is claimed here.

Next validation should compare unchanged recognition controls with and without
this scheduling policy on short speech, a continuous clip exceeding 30 seconds,
and longer recordings with natural pauses. Inspect names, negations, language
switches, repeated words, and endings. A scheduling improvement must not be
reported as completed punctuation, paragraphs, or spoken-correction handling.
