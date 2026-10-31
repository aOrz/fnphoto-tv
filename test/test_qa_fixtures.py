"""Local ephemeral HTTP/WS checks for synthetic QA fixtures; never contacts a NAS."""
import base64
import io
import json
from pathlib import Path
import tempfile
import unittest

from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives import padding, serialization
from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from PIL import Image

import qa_server as qa
import test_server as mock


class FixtureDataTests(unittest.TestCase):
    def tearDown(self):
        qa.configure_fixture("demo")

    def snapshot(self):
        return json.dumps({"photos": mock.PHOTOS, "albums": mock.ALBUMS,
                           "persons": mock.PERSONS, "timeline": mock.TIMELINE_ITEMS}, sort_keys=True)

    def test_repeated_initialization_is_identical_not_appended(self):
        qa.configure_fixture("large")
        first = self.snapshot()
        mock.PHOTO_MAP[29]["isCollect"] = 0
        qa.configure_fixture("large")
        self.assertEqual(self.snapshot(), first)
        self.assertEqual(len(mock.PHOTOS), 360)
        self.assertEqual(len(mock.ALBUMS), 1)
        self.assertEqual(len(mock.ALBUM_MAP[1]["photos"]), 360)
        self.assertEqual(mock.ALBUMS[0]["photoCount"] + mock.ALBUMS[0]["videoCount"], 360)

    def test_named_scenarios_and_single_favorite_date_are_stable(self):
        qa.configure_fixture("video")
        for identifier in (124, 151, 314):
            self.assertEqual(mock.PHOTO_MAP[identifier]["category"], "video")
            self.assertEqual(mock.PHOTO_MAP[identifier]["fileName"], f"test_video_{identifier:04d}.mp4")
        latest = [p for p in mock.PHOTOS if p["photoDateTime"].startswith("2026-10-30")]
        self.assertEqual([p["id"] for p in latest], [29])
        self.assertEqual(latest[0]["isCollect"], 1)
        self.assertEqual(mock.PERSONS[0]["name"], "Mom")

    def test_image_bytes_do_not_depend_on_request_order(self):
        first = qa.thumbnail(240, 180, "test_photo_0029.jpg", "xs")
        qa.thumbnail(240, 180, "another item", "xs")
        self.assertEqual(first, qa.thumbnail(240, 180, "test_photo_0029.jpg", "xs"))
        with Image.open(io.BytesIO(first)) as image:
            self.assertEqual(image.format, "JPEG")
            self.assertEqual(image.size, (240, 180))


class FixtureHttpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="fnphoto-fixture-test-")
        self.root = Path(self.directory.name)
        self.media, self.state = self.root / "media", self.root / "state"
        self.media.mkdir()
        # Transport/range fixture only. Real MP4 decode parameters are checked by
        # test_generate_qa_media.py, not inferred from these arbitrary bytes.
        self.a = b"synthetic-A-byte-range-fixture-0123456789"
        self.b = b"synthetic-B-byte-range-fixture-9876543210"
        (self.media / "clip_a.mp4").write_bytes(self.a)
        (self.media / "clip_b.mp4").write_bytes(self.b)
        self.trace = self.root / "requests.jsonl"
        server = TestServer(qa.build_qa_app("video", self.media, self.state, self.trace))
        await server.start_server(access_log=None)
        self.client = TestClient(server)
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.directory.cleanup()
        qa.configure_fixture("demo")

    async def data(self, path, **kwargs):
        response = await self.client.get(path, **kwargs)
        self.assertEqual(response.status, 200)
        return (await response.json())["data"]

    async def test_real_http_album_offsets_cover_360_unique_items(self):
        identifiers = []
        offset = 0
        while True:
            data = await self.data("/p/api/v1/album/photos", params={"album_id": 1, "limit": 35, "offset": offset})
            self.assertEqual(data["total"], 360)
            self.assertLessEqual(data["count"], 35)
            identifiers.extend(p["id"] for p in data["list"])
            offset += data["count"]
            if not data["hasNext"]:
                break
        self.assertEqual(len(identifiers), 360)
        self.assertEqual(set(identifiers), set(range(1, 361)))
        trace = [json.loads(line) for line in self.trace.read_text().splitlines()]
        self.assertEqual([event["offset"] for event in trace], list(range(0, 360, 35)))

    async def test_favorite_remove_and_restore_updates_real_http_timeline(self):
        for collected in (0, 0, 1):
            response = await self.client.post("/p/api/v1/photo/collect", data={"id": "29", "collect": str(collected)})
            self.assertEqual(response.status, 200)
            self.assertEqual((await response.json())["errno"], 0)
            detail = await self.data("/p/api/v1/photo/detail/29")
            self.assertEqual(detail["info"]["isCollect"], collected)
            timeline = await self.data("/p/api/v1/gallery/timeline", params={"is_collect": 1})
            day = [p for p in timeline["list"] if (p["year"], p["month"], p["day"]) == (2026, 10, 30)]
            self.assertEqual(sum(p["itemCount"] for p in day), collected)

    async def test_missing_then_local_control_restore_range_then_invalid(self):
        missing = await self.client.get("/p/api/v1/stream/v/124")
        self.assertEqual(missing.status, 404)
        await missing.read()
        qa.set_video_mode(self.state, "restored")
        restored = await self.client.get("/p/api/v1/stream/v/124", headers={"Range": "bytes=2-8"})
        self.assertEqual(restored.status, 206)
        self.assertEqual(await restored.read(), self.a[2:9])
        self.assertEqual(restored.headers["Content-Range"], f"bytes 2-8/{len(self.a)}")
        self.assertEqual(restored.headers["Cache-Control"], "no-store")
        qa.set_video_mode(self.state, "invalid")
        invalid = await self.client.get("/p/api/v1/stream/v/124")
        self.assertEqual(invalid.status, 200)
        self.assertIn("video/mp4", invalid.headers["Content-Type"])
        self.assertIn(b"intentional synthetic decoder failure", await invalid.read())
        traces = [json.loads(line) for line in self.trace.read_text().splitlines()]
        self.assertEqual([e["video_mode"] for e in traces], ["missing", "restored", "invalid"])
        self.assertEqual([e["status"] for e in traces], [404, 206, 200])

    async def test_normal_a_and_b_remain_independent_of_failure_mode(self):
        qa.set_video_mode(self.state, "invalid")
        for identifier, content in ((151, self.a), (314, self.b)):
            response = await self.client.get(f"/p/api/v1/stream/v/{identifier}")
            self.assertEqual(response.status, 200)
            self.assertEqual(await response.read(), content)

    async def test_bad_state_and_missing_media_fail_clearly(self):
        self.state.mkdir()
        (self.state / "video-mode.json").write_text('{"video_mode":"unknown"}')
        self.assertEqual((await self.client.get("/p/api/v1/stream/v/124")).status, 503)
        (self.media / "clip_a.mp4").unlink()
        self.assertEqual((await self.client.get("/p/api/v1/stream/v/151")).status, 503)
        self.assertEqual((await self.client.get("/p/api/v1/stream/v/9999")).status, 404)
        self.assertEqual((await self.client.post("/__qa/control", json={"video_mode": "restored"})).status, 404)

    async def test_trace_omits_auth_values_and_unknown_query_parameters(self):
        response = await self.client.get("/p/api/v1/stream/v/124?token=DO_NOT_RECORD&user=PRIVATE_USER",
                                         headers={"accesstoken": "DO_NOT_RECORD", "authx": "PRIVATE_HEADER"})
        await response.read()
        trace = self.trace.read_text()
        for value in ("DO_NOT_RECORD", "PRIVATE_USER", "PRIVATE_HEADER", "token", "accesstoken", "authx"):
            self.assertNotIn(value, trace)
        self.assertEqual(json.loads(trace)["route"], "/p/api/v1/stream/v/{id}")

    async def test_http_thumbnail_is_a_real_decodable_synthetic_jpeg(self):
        response = await self.client.get("/p/api/v1/stream/p/t/29/o/fixture?size=xs")
        self.assertEqual(response.status, 200)
        self.assertIn("image/jpeg", response.headers["Content-Type"])
        with Image.open(io.BytesIO(await response.read())) as image:
            self.assertEqual(image.size, (240, 180))

    async def test_mock_websocket_login_and_heartbeat_accept_synthetic_account(self):
        async with self.client.ws_connect("/websocket") as ws:
            await ws.send_json({"req": "util.crypto.getRSAPub"})
            public = await ws.receive_json(timeout=5)
            key, iv = b"0123456789abcdef", b"fedcba9876543210"
            payload = json.dumps({"user": "test", "password": "test", "deviceName": "Synthetic QA"}).encode()
            padder = padding.PKCS7(128).padder()
            padded = padder.update(payload) + padder.finalize()
            encryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
            cipher = encryptor.update(padded) + encryptor.finalize()
            rsa_key = serialization.load_pem_public_key(public["pub"].encode())
            await ws.send_json({"req": "encrypted",
                "rsa": base64.b64encode(rsa_key.encrypt(key, rsa_padding.PKCS1v15())).decode(),
                "aes": base64.b64encode(cipher).decode(), "iv": base64.b64encode(iv).decode()})
            login = await ws.receive_json(timeout=5)
            self.assertEqual(login["result"], "succ")
        async with self.client.ws_connect("/ws/main", params={"token": login["token"]}) as ws:
            await ws.send_json({"type": "heartbeat"})
            self.assertEqual((await ws.receive_json(timeout=5))["type"], "heartbeat")
        self.assertNotIn(login["token"], self.trace.read_text())


if __name__ == "__main__":
    unittest.main()
