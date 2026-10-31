#!/usr/bin/env python3
"""Visual helper for a manually opened, already-playing synthetic video on an emulator.

Example (after manually checking the selected emulator and starting QA video A/B):
  python test/video_smoke.py --serial emulator-5554 --confirm-synthetic-video-playing

Requires Pillow and an existing, authorized adb connection. Never installs an APK,
logs in, starts playback, changes device settings, or chooses a device implicitly.
Only reads emulator properties/screenshots and sends MEDIA_PLAY_PAUSE (85) twice.
Color/motion checks are heuristics, NOT proof of decoding, correct content, audio,
remote focus, or complete UI functionality. Inspect the PNGs/timecodes yourself.
"""
import argparse
from datetime import datetime, timezone
import io
import json
import math
from pathlib import Path
import subprocess
import sys
import time
import uuid


DEFAULT_OUTPUT = Path(__file__).resolve().parent / ".generated" / "evidence"
ROI = (0.20, 0.42, 0.80, 0.72)  # Generated clip title ends at 40%; controls sit at the bottom.
MIN_COLORS = 8
MIN_CHROMATIC_FRACTION = 0.10
MIN_MOTION_FRACTION = 0.01
MAX_PAUSED_MOTION_FRACTION = 0.001


class SmokeError(RuntimeError):
    pass


def pillow_image():
    try:
        from PIL import Image
    except ImportError as exc:
        raise SmokeError("Pillow is required; install test/requirements.txt first") from exc
    return Image


def positive_seconds(value):
    try:
        number = float(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be a finite positive number") from exc
    if not math.isfinite(number) or not 0 < number <= 300:
        raise argparse.ArgumentTypeError("must be greater than 0 and at most 300 seconds")
    return number


def device_serial(value):
    if not value or value.startswith("-") or any(c.isspace() for c in value):
        raise argparse.ArgumentTypeError("provide one explicit adb serial, without whitespace")
    return value


def parser():
    result = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    result.add_argument("--serial", required=True, type=device_serial, help="Explicit authorized emulator serial; physical devices are rejected")
    result.add_argument("--adb", default="adb", help="adb executable path, or name resolved through PATH")
    result.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT, help="Evidence parent directory; a unique run subdirectory is created")
    result.add_argument("--confirm-synthetic-video-playing", action="store_true", help="Confirm you manually checked this emulator is playing the synthetic QA video, with enough duration remaining")
    result.add_argument("--interval-seconds", type=positive_seconds, default=2.0, help="Interval between playing/resumed screenshots (default: 2)")
    result.add_argument("--pause-seconds", type=positive_seconds, default=4.0, help="Interval between paused screenshots (default: 4)")
    result.add_argument("--settle-seconds", type=positive_seconds, default=1.0, help="Wait after each media key (default: 1)")
    result.add_argument("--command-timeout", type=positive_seconds, default=90.0, help="Per-adb-command timeout (default: 90); timeout means key delivery can be uncertain")
    return result


def decode_png(data):
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise SmokeError("adb screencap did not return a PNG")
    Image = pillow_image()
    try:
        with Image.open(io.BytesIO(data)) as source:
            source.load()
            if source.width < 32 or source.height < 32:
                raise SmokeError("screenshot is too small for a useful central ROI")
            return source.convert("RGB")
    except SmokeError:
        raise
    except Exception as exc:
        raise SmokeError("invalid or incomplete screencap PNG") from exc


def central_sample(image):
    w, h = image.size
    box = tuple(int(value * (w if index % 2 == 0 else h)) for index, value in enumerate(ROI))
    return image.convert("RGB").crop(box).resize((64, 48), pillow_image().Resampling.BILINEAR)


def color_metrics(image):
    data = central_sample(image).tobytes()
    pixels = [data[i:i + 3] for i in range(0, len(data), 3)]
    return {"quantized_colors": len({tuple(channel // 32 for channel in pixel) for pixel in pixels}),
            "chromatic_pixel_fraction": sum(max(pixel) - min(pixel) > 32 for pixel in pixels) / len(pixels)}


def color_count(image):
    return color_metrics(image)["quantized_colors"]


def colorful(image):
    metrics = color_metrics(image)
    return metrics["quantized_colors"] >= MIN_COLORS and metrics["chromatic_pixel_fraction"] >= MIN_CHROMATIC_FRACTION


def motion_metrics(first, second):
    if first.size != second.size:
        raise SmokeError("screenshot dimensions changed during the run")
    a, b = central_sample(first).tobytes(), central_sample(second).tobytes()
    differences = [abs(left - right) for left, right in zip(a, b)]
    changed = sum(max(differences[i:i + 3]) > 8 for i in range(0, len(differences), 3))
    return {"changed_pixel_fraction": changed / (len(differences) // 3),
            "mean_absolute_channel_difference": sum(differences) / (len(differences) * 255)}


class Adb:
    def __init__(self, executable, serial, timeout, events):
        self.prefix = [executable, "-s", serial]
        self.timeout = timeout
        self.events = events

    def command(self, *arguments):
        event = {"action": "adb", "arguments": list(arguments), "utc_epoch": time.time()}
        self.events.append(event)
        started = time.monotonic()
        try:
            result = subprocess.run(self.prefix + list(arguments), stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    timeout=self.timeout, check=False)
            if result.returncode:
                event["result"] = "nonzero exit"
                message = result.stderr.decode("utf-8", errors="replace").strip()[:500]
                raise SmokeError(f"adb failed ({result.returncode}): {message}")
            event["result"] = "ok"
            return result.stdout
        except subprocess.TimeoutExpired as exc:
            event["result"] = "timeout; command delivery may be uncertain"
            raise SmokeError("adb command timed out; if this was a media key, inspect playback manually before another toggle") from exc
        except OSError as exc:
            event["result"] = "execution error"
            raise SmokeError(f"could not execute adb: {exc}") from exc
        finally:
            event["elapsed_seconds"] = time.monotonic() - started

    def verify_emulator(self):
        if self.command("get-state").strip() != b"device":
            raise SmokeError("selected adb device is not ready/authorized")
        if self.command("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
            raise SmokeError("selected serial is not an Android emulator; no media keys sent")

    def screenshot(self, destination):
        data = self.command("exec-out", "screencap", "-p")
        image = decode_png(data)
        destination.write_bytes(data)
        return image

    def toggle(self):
        self.command("shell", "input", "keyevent", "85")


def run_smoke(args, client=None, sleep=time.sleep):
    """Return (exit_code, report_path). Injected clients are for offline tests only."""
    if not args.confirm_synthetic_video_playing:
        raise SmokeError("manual synthetic-video confirmation is required before any device operation")
    pillow_image()
    run_name = datetime.now(timezone.utc).strftime("video-smoke-%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    output = args.output_dir / run_name
    output.mkdir(parents=True, exist_ok=False)
    report_path = output / "result.json"
    report = {"schema_version": 1, "status": "running", "serial": args.serial,
              "kind": "synthetic_video_visual_heuristic", "manual_precondition_confirmed": True,
              "device_ui_test_performed": False, "roi_fractions": list(ROI),
              "thresholds": {"minimum_quantized_colors": MIN_COLORS,
                             "minimum_chromatic_fraction": MIN_CHROMATIC_FRACTION,
                             "minimum_motion_fraction": MIN_MOTION_FRACTION,
                             "maximum_paused_motion_fraction": MAX_PAUSED_MOTION_FRACTION,
                             "changed_pixel_channel_delta": 8},
              "limitations": ["Visual heuristic only, not proof of decoding/content/audio correctness.",
                              "Manually review screenshots and burned-in timecodes.",
                              "No login, installation, device setting changes, or full UI regression."],
              "events": [], "screenshots": [], "checks": {}}
    adb = client or Adb(args.adb, args.serial, args.command_timeout, report["events"])
    resume_needed = False
    exit_code = 1

    def shot(name):
        image = adb.screenshot(output / name)
        report["screenshots"].append({"file": name, "utc_epoch": time.time(),
                                      "width": image.width, "height": image.height,
                                      "roi_color_metrics": color_metrics(image)})
        return image

    try:
        adb.verify_emulator()
        before = shot("01-playing.png")
        sleep(args.interval_seconds)
        moving = shot("02-playing-later.png")
        baseline = motion_metrics(before, moving)
        report["checks"]["playing_motion"] = baseline
        if not (colorful(before) and colorful(moving)) or baseline["changed_pixel_fraction"] < MIN_MOTION_FRACTION:
            raise SmokeError("precondition not observed: central ROI needs colorful moving synthetic video; no keys sent")

        report["device_ui_test_performed"] = client is None
        report["pause_command_outcome"] = "uncertain until command returns"
        adb.toggle()
        resume_needed = True
        report["pause_command_outcome"] = "command completed; visual pause still requires checking"
        sleep(args.settle_seconds)
        paused = shot("03-paused.png")
        sleep(args.pause_seconds)
        paused_later = shot("04-paused-later.png")
        report["checks"]["paused_motion"] = motion_metrics(paused, paused_later)

        # Clear before sending: do not blindly retry an uncertain toggle on timeout.
        resume_needed = False
        report["resume_command_outcome"] = "uncertain until command returns"
        adb.toggle()
        report["resume_command_outcome"] = "command completed; visual resume still requires checking"
        sleep(args.settle_seconds)
        resumed = shot("05-resumed.png")
        sleep(args.interval_seconds)
        resumed_later = shot("06-resumed-later.png")
        report["checks"]["resumed_motion"] = motion_metrics(resumed, resumed_later)
        report["checks"]["pause_stable"] = report["checks"]["paused_motion"]["changed_pixel_fraction"] <= MAX_PAUSED_MOTION_FRACTION
        report["checks"]["resume_moving"] = (report["checks"]["resumed_motion"]["changed_pixel_fraction"] >= MIN_MOTION_FRACTION
                                                and colorful(resumed) and colorful(resumed_later))
        if not report["checks"]["pause_stable"] or not report["checks"]["resume_moving"]:
            raise SmokeError("visual pause/resume heuristic failed; inspect PNGs and current playback manually")
        report["status"] = "heuristics_passed_not_full_video_verification"
        exit_code = 0
    except (Exception, KeyboardInterrupt) as exc:
        report["status"] = "failed"
        report["error"] = str(exc) or type(exc).__name__
    finally:
        if resume_needed:
            # A completed pause followed by a capture failure should not knowingly leave playback paused.
            try:
                adb.toggle()
                report["cleanup_resume"] = "one toggle sent after completed pause; manually verify playback"
            except (Exception, KeyboardInterrupt) as exc:
                report["cleanup_resume"] = "uncertain/failed; no further toggles: " + str(exc)
        report["finished_utc"] = datetime.now(timezone.utc).isoformat()
        report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return exit_code, report_path


def main(argv=None):
    cli = parser()
    args = cli.parse_args(argv)
    if not args.confirm_synthetic_video_playing:
        cli.error("manually verify the selected emulator is playing a synthetic QA video, then add --confirm-synthetic-video-playing")
    try:
        code, path = run_smoke(args)
    except (Exception, KeyboardInterrupt) as exc:
        print(f"Video smoke not completed: {exc}", file=sys.stderr)
        return 1
    print(f"{'Heuristics passed (not full video verification)' if code == 0 else 'FAILED / INCONCLUSIVE'}: {path}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
