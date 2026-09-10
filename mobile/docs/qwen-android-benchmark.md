# Qwen Android benchmark

This is a raw recognition experiment, not a production engine. It accepts 16 kHz mono PCM WAV files up to 30 seconds. No streaming, vocabulary hints, cleanup, or long-recording support is claimed.

The runner opts in only when given `--qwen-model-dir`. It passes both `NOTEIT_BENCHMARK=true` and `NOTEIT_QWEN_BENCHMARK=true` during build and test. The Qwen source and AAR are omitted from ordinary builds. The runner verifies all six model files, copies them only into `ai.voiceflow.voiceflow_mobile.benchmark`, verifies destination hashes, and removes its exact temporary upload files. It never clears or uninstalls the personal app.

Use a manifest configuration such as:

```json
{"id":"qwen-4t","engine":"qwen","threads":4,"pipeline":false,"paced":false,"cold":false,"semanticCleanup":false}
```

Append this option to the existing `scripts/local_benchmark.py device` invocation:

```sh
--qwen-model-dir build/benchmarks/qwen-host/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25
```

Manual Flutter builds need both explicit definitions:

```sh
flutter build apk --debug --dart-define=NOTEIT_BENCHMARK=true --dart-define=NOTEIT_QWEN_BENCHMARK=true
```

The pinned [official sherpa-onnx 1.13.7 AAR](https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar) must be at `build/benchmarks/qwen-android/sherpa-onnx-1.13.7.aar`: 49,113,869 bytes, SHA-256 `c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8`. Gradle verifies it before including it. The six model hashes are pinned in the runner helper and native adapter.

Defaults match the host screen: CPU, four threads, 128 features, automatic language detection, maximum total/new token lengths 512, temperature 0.000001, top-p 0.8, seed 42. Streams use the plain constructor; no unsupported hotword overload.

`loadMs` includes first-load file verification and recognizer construction. `recognitionMs` measures synchronous decode. `processingMs` includes stream creation, waveform acceptance, decode, result extraction, and stream release. `postStopMs` uses processing time plus load only for a cold configuration; a warm configuration excludes preparation even on its first load. PSS/native heap are sampled after decode, not peak memory. These unpaced results are not the app's live-recording latency acceptance test. Cancellation waits for synchronous decode to return before releasing native memory.
