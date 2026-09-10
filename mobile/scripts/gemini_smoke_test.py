#!/usr/bin/env python3
"""One small Gemini access check; never logs keys or retries transcription requests."""

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import wave
from pathlib import Path


BASE = "https://generativelanguage.googleapis.com"


class SmokeFailure(Exception):
    pass


def read_key(path):
    # Gemini keys use plain ASCII without Java-properties escapes.
    if not path.is_file():
        raise SmokeFailure("missing_key_file")
    for line in path.read_text().splitlines():
        if line.strip().startswith("GEMINI_API_KEY="):
            value = line.split("=", 1)[1].strip()
            if value:
                return value
    raise SmokeFailure("missing_key")


def request(method, url, *, key=None, body=None, headers=None):
    request_headers = dict(headers or {})
    if key:
        request_headers["x-goog-api-key"] = key
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        request_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            payload = response.read(4 * 1024 * 1024)
            return response.headers, json.loads(payload) if payload else {}
    except urllib.error.HTTPError as error:
        # Report bounded identifiers and a redacted message, never request headers.
        status = "UNKNOWN"
        try:
            detail = json.loads(error.read(64 * 1024)).get("error", {})
            candidate = detail.get("status", "UNKNOWN")
            if re.fullmatch(r"[A-Z_]{1,64}", str(candidate)):
                status = candidate
            message = detail.get("message", "")
            if isinstance(message, str) and message:
                message = message.replace(key, "[redacted]") if key else message
                message = re.sub(r"AIza[A-Za-z0-9_-]+", "[redacted]", message)
                print("provider_error=" + " ".join(message.split())[:400])
        except (ValueError, AttributeError):
            pass
        raise SmokeFailure(f"http_{error.code}_{status}") from None
    except (urllib.error.URLError, TimeoutError):
        raise SmokeFailure("network_error") from None


def transcript(response):
    if response.get("status", "completed") != "completed":
        raise SmokeFailure("interaction_not_completed")
    if response.get("output_text"):
        return response["output_text"].strip()
    parts = []
    for step in response.get("steps", []):
        if step.get("type") == "model_output":
            parts.extend(step.get("content", []))
    if not parts:
        parts = response.get("outputs", [])
    return "\n".join(p.get("text", "") for p in parts if p.get("type") == "text" and not p.get("thought")).strip()


def run(audio, key):
    remote_name = None
    try:
        headers, _ = request("POST", BASE + "/upload/v1beta/files", key=key,
            headers={"X-Goog-Upload-Protocol": "resumable", "X-Goog-Upload-Command": "start",
                     "X-Goog-Upload-Header-Content-Length": str(audio.stat().st_size),
                     "X-Goog-Upload-Header-Content-Type": "audio/wav"},
            body={"file": {"display_name": "noteit-smoke-test"}})
        upload_url = headers.get("x-goog-upload-url", "")
        parsed = urllib.parse.urlparse(upload_url)
        if parsed.scheme != "https" or not (parsed.hostname or "").endswith(".googleapis.com"):
            raise SmokeFailure("invalid_upload_address")
        _, upload = request("POST", upload_url,
            headers={"X-Goog-Upload-Offset": "0", "X-Goog-Upload-Command": "upload, finalize",
                     "Content-Type": "audio/wav"}, body=audio.read_bytes())
        remote = upload["file"]
        remote_name = remote["name"]
        if not re.fullmatch(r"files/[A-Za-z0-9_-]+", remote_name):
            remote_name = None
            raise SmokeFailure("invalid_remote_file")
        for _ in range(30):
            if remote.get("state") != "PROCESSING":
                break
            time.sleep(1)
            _, remote = request("GET", BASE + "/v1beta/" + remote_name, key=key)
        if remote.get("state") in ("PROCESSING", "FAILED"):
            raise SmokeFailure("upload_not_ready")
        # Exactly one transcription request. No automatic model or mode fallback.
        _, result = request("POST", BASE + "/v1beta/interactions", key=key, body={
            "model": "gemini-3.5-transcribe",
            "input": [{"type": "audio", "uri": remote["uri"], "mime_type": "audio/wav"}],
            "generation_config": {"transcription_config": {"mode": "smart"}},
        })
        text = transcript(result)
        if not text:
            raise SmokeFailure("empty_transcript")
        print("status=success")
        print("transcript=" + text[:1000].replace(key, "[redacted]"))
    finally:
        if remote_name:
            try:
                request("DELETE", BASE + "/v1beta/" + remote_name, key=key)
                print("remote_cleanup=deleted")
            except SmokeFailure:
                print("remote_cleanup=deferred_to_provider_expiry")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wav", nargs="?", type=Path, help="Optional short WAV; otherwise synthesize a short English sample on macOS.")
    parser.add_argument("--check-model", action="store_true", help="Read model metadata without uploading or transcribing audio.")
    parser.add_argument("--check-audio", action="store_true", help="Validate the PCM WAV locally without any API request.")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    try:
        key = read_key(repo / "android/secrets.properties")
        if args.check_model:
            _, model = request("GET", BASE + "/v1beta/models/gemini-3.5-transcribe", key=key)
            print("status=model_available")
            print("model=" + str(model.get("name", "")))
            print("methods=" + ",".join(model.get("supportedGenerationMethods", [])))
            return 0
        with tempfile.TemporaryDirectory(prefix="noteit-gemini-smoke-") as temporary:
            audio = args.wav
            if audio is None:
                if not shutil.which("say") or not shutil.which("afconvert"):
                    raise SmokeFailure("provide_a_short_wav")
                aiff = Path(temporary) / "sample.aiff"
                audio = Path(temporary) / "sample.wav"
                subprocess.run(["say", "-o", str(aiff), "Hello. This is a short transcription test."], check=True, capture_output=True)
                subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", str(aiff), str(audio)], check=True, capture_output=True)
            if not audio.is_file() or not 44 < audio.stat().st_size <= 2 * 1024 * 1024:
                raise SmokeFailure("provide_a_wav_below_2mb")
            with wave.open(str(audio), "rb") as recording:
                if (recording.getnchannels(), recording.getsampwidth(), recording.getframerate(), recording.getcomptype()) != (1, 2, 16000, "NONE"):
                    raise SmokeFailure("expected_16khz_mono_pcm16_wav")
                duration = recording.getnframes() / recording.getframerate()
            if duration < 0.1:
                raise SmokeFailure("empty_synthesized_audio_allow_macos_speech_or_provide_wav")
            if args.check_audio:
                print(f"status=valid_pcm16_wav duration_seconds={duration:.2f}")
                return 0
            run(audio, key)
        return 0
    except SmokeFailure as error:
        print("status=failed reason=" + str(error))
    except Exception as error:
        # Exception strings from libraries or subprocesses must not reveal configuration.
        print("status=failed reason=" + type(error).__name__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
