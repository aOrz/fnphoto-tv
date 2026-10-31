"""Offline tests only: synthetic Pillow images and mocked adb, never real device UI.

Run: python -m unittest discover -s test -p 'test_video_smoke.py' -v
"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

from PIL import Image, ImageDraw

_SPEC = importlib.util.spec_from_file_location("video_smoke", Path(__file__).with_name("video_smoke.py"))
smoke = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(smoke)


def frame(offset=0):
    image = Image.new("RGB", (320, 180))
    draw = ImageDraw.Draw(image)
    for x in range(0, 320, 8):
        for y in range(0, 180, 8):
            draw.rectangle((x, y, x + 7, y + 7), fill=((x + offset) % 256, (y * 3 + offset) % 256, (x + y * 2 + offset) % 256))
    return image


def png_bytes(image):
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


class FakeAdb:
    def __init__(self, images, toggle_error_at=None):
        self.images = iter(images)
        self.toggles = 0
        self.verified = False
        self.toggle_error_at = toggle_error_at

    def verify_emulator(self):
        self.verified = True

    def screenshot(self, destination):
        image = next(self.images)
        if isinstance(image, Exception):
            raise image
        destination.write_bytes(png_bytes(image))
        return image

    def toggle(self):
        self.toggles += 1
        if self.toggles == self.toggle_error_at:
            raise smoke.SmokeError("timeout; toggle delivery uncertain")


class VisualMetricsTests(unittest.TestCase):
    def test_moving_colorful_frames_and_identical_pause(self):
        self.assertGreaterEqual(smoke.color_count(frame()), 16)
        self.assertGreater(smoke.motion_metrics(frame(), frame(40))["changed_pixel_fraction"], .01)
        self.assertEqual(smoke.motion_metrics(frame(), frame())["changed_pixel_fraction"], 0)

    def test_roi_ignores_top_title_and_bottom_player_controls(self):
        first, second = frame(), frame()
        draw = ImageDraw.Draw(second)
        draw.rectangle((0, 0, 319, 35), fill="white")
        draw.rectangle((0, 140, 319, 179), fill="white")
        self.assertEqual(smoke.motion_metrics(first, second)["changed_pixel_fraction"], 0)

    def test_black_frame_is_not_colorful_and_changed_dimensions_fail(self):
        self.assertEqual(smoke.color_count(Image.new("RGB", (320, 180))), 1)
        self.assertFalse(smoke.colorful(frame().convert("L").convert("RGB")))
        with self.assertRaisesRegex(smoke.SmokeError, "dimensions changed"):
            smoke.motion_metrics(frame(), Image.new("RGB", (640, 360)))

    def test_repository_synthetic_clips_fit_roi_and_color_checks(self):
        spec = importlib.util.spec_from_file_location("qa_smoke_frames", Path(__file__).with_name("generate_qa_media.py"))
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        for label in ("A", "B"):
            for index in (0, 48, 96, 144, 192, 240):
                with self.subTest(label=label, frame=index):
                    first = generator.make_frame(label, index, 720)
                    second = generator.make_frame(label, index + 48, 720)
                    self.assertTrue(smoke.colorful(first))
                    self.assertTrue(smoke.colorful(second))
                    self.assertGreaterEqual(smoke.motion_metrics(first, second)["changed_pixel_fraction"], smoke.MIN_MOTION_FRACTION)

    def test_screencap_png_validation(self):
        self.assertEqual(smoke.decode_png(png_bytes(frame())).size, (320, 180))
        for invalid in (b"adb error", b"\x89PNG\r\n\x1a\ntruncated", png_bytes(Image.new("RGB", (1, 1)))):
            with self.subTest(data=invalid[:20]), self.assertRaises(smoke.SmokeError):
                smoke.decode_png(invalid)


class AdbCommandTests(unittest.TestCase):
    def test_every_command_has_explicit_serial_and_no_shell(self):
        events = []
        adb = smoke.Adb("custom-adb", "emulator-5556", 12, events)
        responses = [b"device\n", b"1\n", png_bytes(frame()), b""]
        with tempfile.TemporaryDirectory() as temp, mock.patch.object(smoke.subprocess, "run", side_effect=[subprocess.CompletedProcess([], 0, data, b"") for data in responses]) as run:
            adb.verify_emulator()
            adb.screenshot(Path(temp) / "frame.png")
            adb.toggle()
        self.assertEqual([call.args[0][3:] for call in run.call_args_list], [
            ["get-state"], ["shell", "getprop", "ro.kernel.qemu"],
            ["exec-out", "screencap", "-p"], ["shell", "input", "keyevent", "85"]])
        for call in run.call_args_list:
            self.assertEqual(call.args[0][:3], ["custom-adb", "-s", "emulator-5556"])
            self.assertNotIn("shell", call.kwargs)
            self.assertEqual(call.kwargs["timeout"], 12)
        self.assertEqual(len(events), 4)

    def test_non_emulator_rejected_before_media_commands(self):
        adb = smoke.Adb("adb", "explicit-physical-serial", 12, [])
        with mock.patch.object(smoke.subprocess, "run", side_effect=[subprocess.CompletedProcess([], 0, b"device\n", b""), subprocess.CompletedProcess([], 0, b"0\n", b"")]) as run:
            with self.assertRaisesRegex(smoke.SmokeError, "not an Android emulator"):
                adb.verify_emulator()
        self.assertEqual(run.call_count, 2)

    def test_command_failure_timeout_and_missing_executable_are_errors(self):
        adb = smoke.Adb("adb", "emulator-5554", 12, [])
        for failure in (subprocess.CompletedProcess([], 1, b"", b"device unauthorized"), subprocess.TimeoutExpired("adb", 12), FileNotFoundError("adb")):
            kwargs = {"side_effect": failure} if isinstance(failure, Exception) else {"return_value": failure}
            with self.subTest(failure=failure), mock.patch.object(smoke.subprocess, "run", **kwargs), self.assertRaises(smoke.SmokeError):
                adb.toggle()


class SmokeRunTests(unittest.TestCase):
    def run_fake(self, root, images, toggle_error_at=None):
        args = smoke.parser().parse_args(["--serial", "emulator-5554", "--confirm-synthetic-video-playing", "--output-dir", str(root)])
        client = FakeAdb(images, toggle_error_at)
        code, path = smoke.run_smoke(args, client=client, sleep=lambda _: None)
        return code, json.loads(path.read_text()), client, path

    def test_complete_offline_sequence_writes_evidence_not_device_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            code, result, client, path = self.run_fake(temp, [frame(), frame(40), frame(80), frame(80), frame(100), frame(140)])
            self.assertEqual(code, 0)
            self.assertEqual(client.toggles, 2)
            self.assertTrue(client.verified)
            self.assertFalse(result["device_ui_test_performed"])
            self.assertEqual(len(list(path.parent.glob("*.png"))), 6)
            self.assertEqual(result["status"], "heuristics_passed_not_full_video_verification")
            self.assertTrue(result["checks"]["pause_stable"])
            self.assertTrue(result["checks"]["resume_moving"])

    def test_static_or_black_precondition_never_sends_keys(self):
        for first, second in ((frame(), frame()), (Image.new("RGB", (320, 180)), Image.new("RGB", (320, 180)))):
            with self.subTest(), tempfile.TemporaryDirectory() as temp:
                code, result, client, _ = self.run_fake(temp, [first, second])
                self.assertEqual(code, 1)
                self.assertEqual(client.toggles, 0)
                self.assertIn("precondition not observed", result["error"])

    def test_failed_pause_heuristic_is_nonzero_but_resume_still_sent(self):
        with tempfile.TemporaryDirectory() as temp:
            code, result, client, _ = self.run_fake(temp, [frame(), frame(40), frame(60), frame(80), frame(100), frame(140)])
        self.assertEqual(code, 1)
        self.assertFalse(result["checks"]["pause_stable"])
        self.assertEqual(client.toggles, 2)

    def test_capture_failure_after_pause_attempts_one_cleanup_resume(self):
        with tempfile.TemporaryDirectory() as temp:
            code, result, client, _ = self.run_fake(temp, [frame(), frame(40), smoke.SmokeError("bad capture")])
        self.assertEqual(code, 1)
        self.assertEqual(client.toggles, 2)
        self.assertIn("one toggle sent", result["cleanup_resume"])

    def test_uncertain_pause_or_resume_is_not_blindly_retried(self):
        for fail_at in (1, 2):
            with self.subTest(fail_at=fail_at), tempfile.TemporaryDirectory() as temp:
                code, result, client, _ = self.run_fake(temp, [frame(), frame(40), frame(80), frame(80)], toggle_error_at=fail_at)
                self.assertEqual(code, 1)
                self.assertEqual(client.toggles, fail_at)
                self.assertNotIn("cleanup_resume", result)

    def test_missing_confirmation_or_serial_never_runs_adb(self):
        for arguments in ([], ["--serial", "emulator-5554"]):
            with self.subTest(args=arguments), mock.patch.object(smoke.subprocess, "run") as run, contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                smoke.main(arguments)
            self.assertEqual(error.exception.code, 2)
            run.assert_not_called()

    def test_missing_device_cannot_report_ui_pass(self):
        with tempfile.TemporaryDirectory() as temp, mock.patch.object(smoke.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"", b"device not found")):
            args = smoke.parser().parse_args(["--serial", "emulator-9999", "--confirm-synthetic-video-playing", "--output-dir", temp])
            code, path = smoke.run_smoke(args, sleep=lambda _: None)
            result = json.loads(path.read_text())
        self.assertEqual(code, 1)
        self.assertFalse(result["device_ui_test_performed"])
        self.assertEqual(result["status"], "failed")

    def test_defaults_and_invalid_waits(self):
        self.assertEqual(smoke.DEFAULT_OUTPUT, Path(smoke.__file__).resolve().parent / ".generated" / "evidence")
        for value in ("nan", "inf", "0", "-1", "301", "invalid"):
            with self.subTest(value=value), self.assertRaises(Exception):
                smoke.positive_seconds(value)


if __name__ == "__main__":
    unittest.main()
