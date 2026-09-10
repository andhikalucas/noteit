# Local model files

Whisper assets are pinned to official `ggerganov/whisper.cpp` revision `5359861c739e955e79d9a303bcbc70fb988958b1`. Filenames, exact sizes, and SHA-256 digests are recorded in `ModelStore.kt`; downloads never resolve mutable `main` metadata.

Source: https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1

Status reads only local file metadata. Existing downloads are not trusted from their name or size alone: the first model load in each process checks SHA-256 on the inference worker, then caches the verdict against size and modification time. This preserves valid legacy downloads without network access or a mandatory redownload. Before first validation, Settings may show an exact-size legacy file as downloaded; a bad checksum at load requests a fresh download. This avoids hashing a 190 MB file on the UI thread.

Downloads are serialized per destination. Verification finishes before the new file atomically replaces the old file. Failed transfers, verification, or installation leave the prior model intact. Files are in app-private storage; the metadata cache is an optimization for normal app ownership, not a defense against another process with privileged filesystem access preserving timestamps.
