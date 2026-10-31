#!/usr/bin/env python3
"""Generate small, entirely synthetic playback QA fixtures (never downloads media).

    python3 test/generate_qa_media.py
    python3 test/generate_qa_media.py --duration 2 --output-dir /tmp/fnphoto-smoke
    python3 test/generate_qa_media.py --ffmpeg ./tools/ffmpeg

Requires Pillow and an ffmpeg executable with libx264 and AAC encoders. Frames are
streamed one at a time. A/B differ in color, motion, label and tone. The content
and encoding parameters are repeatable; binary hashes can differ across ffmpeg,
encoder and font versions. Generated MP4s are local artifacts, not source files.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

WIDTH, HEIGHT, FPS = 640, 360, 24
DEFAULT_OUTPUT = Path(__file__).resolve().parent / ".generated" / "media"
CLIPS = (("A", "clip_a.mp4", 440), ("B", "clip_b.mp4", 660))


class GenerationError(RuntimeError):
    """An actionable dependency or encoder failure."""


def duration_value(value: str | float) -> float:
    try:
        duration = float(value)
    except (TypeError, ValueError) as error:
        raise ValueError("duration must be a positive finite number of seconds") from error
    if not math.isfinite(duration) or duration <= 0 or duration > 3600:
        raise ValueError("duration must be greater than 0 and at most 3600 seconds")
    return duration


def frame_count(duration: float) -> int:
    return max(1, int(round(duration_value(duration) * FPS)))


def timecode(frame: int) -> str:
    seconds, subframe = divmod(frame, FPS)
    minutes, second = divmod(seconds, 60)
    hour, minute = divmod(minutes, 60)
    return f"{hour:02}:{minute:02}:{second:02}:{subframe:02}"


def pillow_modules():
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError as error:
        raise GenerationError("Pillow is required. Install Pillow in your Python environment before generating media.") from error
    return Image, ImageDraw, ImageFont


def find_ffmpeg(command: str) -> str:
    executable = shutil.which(command)
    if executable is None:
        raise GenerationError(f"ffmpeg executable not found: {command!r}. Install ffmpeg or pass --ffmpeg PATH; nothing is downloaded automatically.")
    return executable


def make_frame(label: str, frame: int, count: int, modules=None):
    """Return one RGB frame; caller releases it before generating the next frame."""
    Image, ImageDraw, ImageFont = modules or pillow_modules()
    if label not in ("A", "B"):
        raise ValueError("label must be A or B")
    phase = frame % (FPS * 4)
    background = (18, 42, 100) if label == "A" else (98, 24, 60)
    image = Image.new("RGB", (WIDTH, HEIGHT), background)
    draw = ImageDraw.Draw(image)
    for x in range(0, WIDTH, 80):
        draw.line((x, 0, x, HEIGHT), fill=(60, 65, 85), width=1)
    for y in range(0, HEIGHT, 60):
        draw.line((0, y, WIDTH, y), fill=(60, 65, 85), width=1)
    x = 25 + int(510 * phase / (FPS * 4 - 1))
    if label == "B":
        x = WIDTH - 80 - x
    draw.rectangle((x, 180, x + 70, 250), fill=(255, 210, 40))
    y = 210 + int(45 * math.sin(frame * 2 * math.pi / (FPS * 3)))
    draw.ellipse((290, y, 350, y + 60), fill=(60, 240, 210))
    draw.rectangle((20, 325, 620, 341), outline=(230, 230, 230), width=2)
    progress = int(596 * (frame + 1) / count)
    draw.rectangle((22, 327, 22 + progress, 339), fill=(70, 220, 130))
    # A bundled Pillow default font avoids reliance on any host font file/path.
    text = Image.new("RGB", (WIDTH // 3, 48), (10, 10, 16))
    text_draw = ImageDraw.Draw(text)
    font = ImageFont.load_default()
    text_draw.text((4, 3), f"CLIP {label} - SYNTHETIC QA", font=font, fill="white")
    text_draw.text((4, 17), f"TC {timecode(frame)}  {FPS} fps", font=font, fill=(255, 220, 80))
    text_draw.text((4, 31), f"FRAME {frame + 1:05}/{count:05}", font=font, fill="white")
    image.paste(text.resize((WIDTH, 144), resample=Image.Resampling.NEAREST), (0, 0))
    return image


def encoder_command(ffmpeg: str, output: Path, count: int, frequency: int) -> list[str]:
    duration = count / FPS
    return [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "-s:v", f"{WIDTH}x{HEIGHT}",
            "-r", str(FPS), "-i", "pipe:0",
            "-f", "lavfi", "-i", f"sine=frequency={frequency}:sample_rate=48000:duration={duration:.9f}",
            "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-profile:v", "baseline",
            "-level:v", "3.0", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-crf", "23",
            "-threads", "1", "-g", str(FPS * 2), "-bf", "0",
            "-c:a", "aac", "-b:a", "96k", "-ac", "1", "-ar", "48000", "-af", "volume=0.3",
            "-t", f"{duration:.9f}", "-shortest", "-movflags", "+faststart", "-map_metadata", "-1", str(output)]


def encode_clip(ffmpeg: str, output: Path, label: str, count: int, frequency: int, modules) -> None:
    # stderr is a file rather than PIPE: encoder diagnostics cannot deadlock stdin.
    with tempfile.TemporaryFile() as errors:
        try:
            process = subprocess.Popen(encoder_command(ffmpeg, output, count, frequency),
                                       stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=errors)
        except OSError as error:
            raise GenerationError(f"Cannot start ffmpeg: {error}") from error
        try:
            try:
                for frame in range(count):
                    with make_frame(label, frame, count, modules) as image:
                        process.stdin.write(image.tobytes())
                process.stdin.close()
            except BrokenPipeError:
                # The diagnostic below explains missing encoders/invalid binaries.
                try:
                    process.stdin.close()
                except BrokenPipeError:
                    pass
            returncode = process.wait()
        except BaseException:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            raise
        if returncode != 0:
            errors.seek(0)
            detail = errors.read(8192).decode("utf-8", errors="replace").strip()
            raise GenerationError(f"ffmpeg failed for clip {label} (exit {returncode}). Ensure libx264 and AAC are available.\n{detail}")


def generate(output_dir: Path, ffmpeg: str = "ffmpeg", duration: float = 30) -> dict:
    requested = duration_value(duration)
    count = frame_count(requested)
    executable = find_ffmpeg(ffmpeg)
    modules = pillow_modules()
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schema_version": 1,
        "synthetic": True,
        "reproducibility": "Same semantic content and encoding settings; byte hashes are not guaranteed across tool versions.",
        "requested_duration_seconds": requested,
        "width": WIDTH, "height": HEIGHT, "fps": FPS,
        "video_codec": "h264", "video_profile": "Baseline", "pixel_format": "yuv420p",
        "audio_codec": "aac", "audio_sample_rate": 48000,
        "clips": [{"label": label, "file": filename, "frames": count,
                   "duration_seconds": count / FPS, "tone_hz": frequency}
                  for label, filename, frequency in CLIPS],
    }
    # Failed/interrupted encoding leaves existing good fixtures untouched.
    with tempfile.TemporaryDirectory(prefix=".qa-media-", dir=output_dir) as staging:
        stage = Path(staging)
        for label, filename, frequency in CLIPS:
            encode_clip(executable, stage / filename, label, count, frequency, modules)
        (stage / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        for filename in [clip[1] for clip in CLIPS] + ["manifest.json"]:
            os.replace(stage / filename, output_dir / filename)
    return manifest


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--ffmpeg", default="ffmpeg", help="ffmpeg executable name or path (no automatic download)")
    parser.add_argument("--duration", type=float, default=30, help="seconds per clip; default 30, use 2 for smoke testing")
    args = parser.parse_args(argv)
    try:
        generate(args.output_dir, args.ffmpeg, args.duration)
    except (GenerationError, ValueError, OSError) as error:
        print(f"Media generation failed: {error}", file=sys.stderr)
        return 1
    print(f"Generated clip_a.mp4, clip_b.mp4 and manifest.json in {args.output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
