"""Generator unit tests plus a two-second ffmpeg/ffprobe integration smoke test.

Run: python3 -m unittest discover -s test -p 'test_generate_qa_media.py' -v
The integration test skips only if local ffmpeg, ffprobe or Pillow is missing.
No tools or media are downloaded and all outputs live in temporary directories.
"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

_SPEC = importlib.util.spec_from_file_location("qa_media_generator", Path(__file__).with_name("generate_qa_media.py"))
generator = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(generator)
HAS_PILLOW = importlib.util.find_spec("PIL") is not None
HAS_MEDIA_TOOLS = shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


class GeneratorTests(unittest.TestCase):
    def test_duration_validation_and_frame_quantization(self):
        self.assertEqual(generator.frame_count(30), 720)
        self.assertEqual(generator.frame_count(2), 48)
        self.assertEqual(generator.frame_count(0.001), 1)
        for invalid in (0, -1, float("nan"), float("inf"), 3601, "not-a-duration"):
            with self.subTest(duration=invalid), self.assertRaises(ValueError):
                generator.duration_value(invalid)

    def test_timecode_rollovers(self):
        self.assertEqual(generator.timecode(0), "00:00:00:00")
        self.assertEqual(generator.timecode(23), "00:00:00:23")
        self.assertEqual(generator.timecode(24), "00:00:01:00")
        self.assertEqual(generator.timecode(24 * 61), "00:01:01:00")

    def test_encoder_arguments_are_portable_and_use_no_shell(self):
        command = generator.encoder_command("custom-ffmpeg", Path("clip.mp4"), 48, 440)
        self.assertEqual(command[0], "custom-ffmpeg")
        self.assertIn("pipe:0", command)
        self.assertEqual(command[command.index("-profile:v") + 1], "baseline")
        self.assertEqual(command[command.index("-c:a") + 1], "aac")
        self.assertEqual(command[command.index("-pix_fmt", command.index("-profile:v")) + 1], "yuv420p")
        self.assertEqual(command[-1], "clip.mp4")
        self.assertEqual(generator.DEFAULT_OUTPUT, Path(generator.__file__).resolve().parent / ".generated" / "media")

    def test_missing_ffmpeg_is_clear_and_does_not_create_outputs(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "not-created"
            with mock.patch.object(generator.shutil, "which", return_value=None), contextlib.redirect_stderr(io.StringIO()) as errors:
                code = generator.main(["--ffmpeg", "missing-ffmpeg", "--output-dir", str(output)])
            self.assertEqual(code, 1)
            self.assertIn("ffmpeg executable not found", errors.getvalue())
            self.assertFalse(output.exists())

    def test_missing_pillow_has_actionable_error(self):
        real_import = __import__
        def without_pillow(name, *args, **kwargs):
            if name == "PIL":
                raise ImportError("Pillow unavailable")
            return real_import(name, *args, **kwargs)
        with mock.patch("builtins.__import__", side_effect=without_pillow):
            with self.assertRaisesRegex(generator.GenerationError, "Pillow is required"):
                generator.pillow_modules()

    def test_failed_encoding_preserves_existing_files_and_removes_staging(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "clip_a.mp4").write_bytes(b"previous-good-file")
            (root / "manifest.json").write_text("previous-manifest", encoding="utf-8")
            def fail_second(ffmpeg, output, label, count, frequency, modules):
                output.write_bytes(b"incomplete-new-file")
                if label == "B":
                    raise generator.GenerationError("missing encoder")
            with mock.patch.object(generator, "find_ffmpeg", return_value="ffmpeg"), mock.patch.object(generator, "pillow_modules", return_value=()), mock.patch.object(generator, "encode_clip", side_effect=fail_second):
                with self.assertRaisesRegex(generator.GenerationError, "missing encoder"):
                    generator.generate(root, duration=2)
            self.assertEqual((root / "clip_a.mp4").read_bytes(), b"previous-good-file")
            self.assertEqual((root / "manifest.json").read_text(encoding="utf-8"), "previous-manifest")
            self.assertEqual(sorted(path.name for path in root.iterdir()), ["clip_a.mp4", "manifest.json"])

    @unittest.skipUnless(HAS_PILLOW, "Pillow not installed")
    def test_frames_are_rgb_repeatable_distinct_and_moving(self):
        with generator.make_frame("A", 0, 48) as first, generator.make_frame("A", 0, 48) as repeat, generator.make_frame("A", 1, 48) as next_frame, generator.make_frame("B", 0, 48) as other:
            self.assertEqual(first.size, (640, 360))
            self.assertEqual(first.mode, "RGB")
            self.assertEqual(first.tobytes(), repeat.tobytes())
            self.assertNotEqual(first.tobytes(), next_frame.tobytes())
            self.assertNotEqual(first.tobytes(), other.tobytes())

    @unittest.skipUnless(HAS_MEDIA_TOOLS and HAS_PILLOW, "requires local ffmpeg, ffprobe and Pillow; nothing is downloaded")
    def test_short_clips_have_requested_video_audio_and_duration(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = generator.generate(root, ffmpeg=shutil.which("ffmpeg"), duration=2)
            self.assertEqual(json.loads((root / "manifest.json").read_text(encoding="utf-8")), manifest)
            self.assertTrue(manifest["synthetic"])
            self.assertEqual(len(manifest["clips"]), 2)
            for clip in manifest["clips"]:
                with self.subTest(clip=clip["label"]):
                    probe = subprocess.run([shutil.which("ffprobe"), "-v", "error", "-show_streams", "-show_format", "-of", "json", str(root / clip["file"])], check=True, capture_output=True, text=True, timeout=20)
                    metadata = json.loads(probe.stdout)
                    video = next(stream for stream in metadata["streams"] if stream["codec_type"] == "video")
                    audio = next(stream for stream in metadata["streams"] if stream["codec_type"] == "audio")
                    self.assertEqual(video["codec_name"], "h264")
                    self.assertIn(video["profile"], ("Baseline", "Constrained Baseline"))
                    self.assertEqual((video["width"], video["height"]), (640, 360))
                    self.assertEqual(video["pix_fmt"], "yuv420p")
                    self.assertEqual(video["avg_frame_rate"], "24/1")
                    self.assertEqual(int(video["nb_frames"]), 48)
                    self.assertEqual(audio["codec_name"], "aac")
                    self.assertEqual(int(audio["sample_rate"]), 48000)
                    self.assertAlmostEqual(float(metadata["format"]["duration"]), 2.0, delta=0.1)
                    self.assertEqual(clip["duration_seconds"], 2)
            self.assertEqual(sorted(path.name for path in root.iterdir()), ["clip_a.mp4", "clip_b.mp4", "manifest.json"])


if __name__ == "__main__":
    unittest.main()
