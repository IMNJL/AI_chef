#!/usr/bin/env python3
import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from vosk import KaldiRecognizer, Model


def resolve_ffmpeg_binary() -> str:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg:
        return ffmpeg

    fallback_locations = [
        "/opt/homebrew/bin/ffmpeg",
        "/usr/local/bin/ffmpeg",
    ]
    for path in fallback_locations:
        if os.path.isfile(path) and os.access(path, os.X_OK):
            return path

    raise RuntimeError(
        "ffmpeg is not installed or not available in PATH. "
        "Expected binary in PATH or /opt/homebrew/bin/ffmpeg."
    )


def transcribe(model_path: Path, input_audio: Path) -> str:
    if not model_path.exists():
        raise RuntimeError(f"Vosk model path does not exist: {model_path}")
    if not input_audio.exists():
        raise RuntimeError(f"Input audio file does not exist: {input_audio}")

    model = Model(str(model_path))
    recognizer = KaldiRecognizer(model, 16000)
    recognizer.SetWords(False)

    ffmpeg_cmd = [
        resolve_ffmpeg_binary(),
        "-loglevel",
        "error",
        "-i",
        str(input_audio),
        "-ar",
        "16000",
        "-ac",
        "1",
        "-f",
        "s16le",
        "-",
    ]
    process = subprocess.Popen(ffmpeg_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if process.stdout is None:
        raise RuntimeError("ffmpeg stdout is unavailable")

    parts: list[str] = []
    while True:
        chunk = process.stdout.read(4000)
        if not chunk:
            break
        if recognizer.AcceptWaveform(chunk):
            result = json.loads(recognizer.Result())
            text = (result.get("text") or "").strip()
            if text:
                parts.append(text)

    final_result = json.loads(recognizer.FinalResult())
    final_text = (final_result.get("text") or "").strip()
    if final_text:
        parts.append(final_text)

    stderr = process.stderr.read().decode("utf-8", errors="replace") if process.stderr else ""
    exit_code = process.wait()
    if exit_code != 0:
        raise RuntimeError(f"ffmpeg exited with code {exit_code}: {stderr.strip()}")

    return " ".join(parts).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--input", required=True)
    args = parser.parse_args()

    text = transcribe(Path(args.model), Path(args.input))
    print(text)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Vosk transcription failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
