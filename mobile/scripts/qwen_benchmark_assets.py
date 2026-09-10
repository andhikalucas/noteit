"""Verify and copy the fixed Qwen package only into the isolated benchmark app."""
import hashlib
from pathlib import Path
import subprocess
import re

PACKAGE = "ai.voiceflow.voiceflow_mobile.benchmark"
MODEL_HASHES = {
    "conv_frontend.onnx": "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e",
    "encoder.int8.onnx": "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9",
    "decoder.int8.onnx": "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771",
    "tokenizer/vocab.json": "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910",
    "tokenizer/merges.txt": "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5",
    "tokenizer/tokenizer_config.json": "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c",
}


def verify_model_dir(directory):
    directory = Path(directory).resolve(strict=True)
    for name, expected in MODEL_HASHES.items():
        digest = hashlib.sha256()
        with (directory / name).open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != expected:
            raise ValueError(f"Qwen asset checksum mismatch: {name}")
    return directory


def push_model_files(command, directory, package_dir, token):
    if not re.fullmatch(r"/data/(?:user/\d+|data)/" + re.escape(PACKAGE), package_dir):
        raise ValueError("Qwen assets can only be copied to the benchmark sandbox.")
    if not re.fullmatch(r"[0-9a-f]{32}", token):
        raise ValueError("Invalid Qwen staging token.")
    destination_dir = f"{package_dir}/files/benchmarks/qwen3-asr-int8"
    command("shell", "run-as", PACKAGE, "mkdir", "-p", destination_dir + "/tokenizer")
    for index, (name, expected) in enumerate(MODEL_HASHES.items()):
        destination = f"{destination_dir}/{name}"
        try:
            existing = command("shell", "run-as", PACKAGE, "sha256sum", destination,
                               capture_output=True, text=True).stdout.split()
            if existing and existing[0] == expected:
                continue
        except subprocess.CalledProcessError:
            pass
        stage = f"/data/local/tmp/noteit-qwen-{token}-{index}"
        incoming = destination + f".incoming-{token}"
        try:
            command("push", str(directory / name), stage, stdout=subprocess.DEVNULL)
            command("shell", "run-as", PACKAGE, "cp", stage, incoming)
            actual = command("shell", "run-as", PACKAGE, "sha256sum", incoming,
                             capture_output=True, text=True).stdout.split()
            if not actual or actual[0] != expected:
                raise ValueError(f"Device Qwen asset checksum mismatch: {name}")
            command("shell", "run-as", PACKAGE, "mv", incoming, destination)
        finally:
            command("shell", "rm", "-f", stage)
            command("shell", "run-as", PACKAGE, "rm", "-f", incoming)
    return destination_dir
