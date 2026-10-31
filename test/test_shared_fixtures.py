"""Document-derived synthetic shared API checks; not evidence of real NAS compatibility."""
import asyncio
import io
from pathlib import Path
import tempfile
import unittest

from aiohttp.test_utils import TestClient, TestServer
from PIL import Image
import qa_server as qa
import test_server as mock

LIST = "/p/api/v1/album_grant/list_to_me"
CONTENT = "/p/api/v1/album/normal/getList"


class SharedFixtureTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.state = Path(self.temp.name)
        self.client = TestClient(TestServer(qa.build_qa_app("shared", state_dir=self.state)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.temp.cleanup()
        qa.configure_fixture("demo")

    def params(self, offset=0, album=9001):
        return {"albumId": str(album), "offset": str(offset), "limit": "50",
                "startTime": "2000:01:01 00:00:00", "endTime": "2099:12:31 23:59:59",
                "sortDirection": "desc"}

    def list_params(self, offset=0, limit=1000):
        return {"offset": str(offset), "limit": str(limit), "sort_by": "share_mod_time",
                "sort_direction": "desc"}

    async def test_received_list_is_distinct_and_supports_offset(self):
        first = await self.client.get(LIST, params=self.list_params(limit=1))
        body = await first.json()
        self.assertEqual(body["code"], 0)
        self.assertEqual(body["data"]["total"], 2)
        album = body["data"]["list"][0]
        self.assertEqual(album["albumId"], 9001)
        self.assertEqual(album["photoCount"] + album["videoCount"], 125)
        self.assertEqual(album["ownerName"], "Synthetic Other Owner")
        second = await self.client.get(LIST, params=self.list_params(offset=1, limit=1))
        self.assertEqual((await second.json())["data"]["list"][0]["albumId"], 9002)
        self.assertNotIn(9001, mock.ALBUM_MAP)
        self.assertFalse(set(range(10001, 10126)) & {p["id"] for p in mock.PHOTOS})

    async def test_three_pages_mixed_media_and_documented_streams(self):
        photos = []
        for offset, size, has_next in ((0, 50, True), (50, 50, True), (100, 25, False)):
            response = await self.client.get(CONTENT, params=self.params(offset))
            self.assertEqual(response.status, 200)
            data = (await response.json())["data"]
            self.assertEqual((data["count"], data["hasNext"]), (size, has_next))
            photos.extend(data["list"])
        self.assertEqual([p["id"] for p in photos], list(range(10001, 10126)))
        self.assertEqual(sum(p["category"] == "video" for p in photos), 12)
        self.assertTrue(all("additional" not in p and p["photoUUID"] for p in photos))
        for size in ("s", "m"):
            response = await self.client.get(f"/p/api/v1/stream/p/t/10001/{size}/shared-fixture-10001")
            self.assertEqual(response.status, 200)
            with Image.open(io.BytesIO(await response.read())) as image:
                self.assertEqual(image.format, "JPEG")

    async def test_empty_album_and_empty_received_list(self):
        response = await self.client.get(CONTENT, params=self.params(album=9002))
        self.assertEqual((await response.json())["data"], {"list": [], "count": 0, "hasNext": False})
        qa.set_shared_mode(self.state, "empty")
        response = await self.client.get(LIST, params=self.list_params())
        self.assertEqual((await response.json())["data"], {"list": [], "total": 0})

    async def test_wrong_query_case_sort_range_and_paging_rejected(self):
        variants = []
        wrong_case = self.params()
        wrong_case["album_id"] = wrong_case.pop("albumId")
        variants.append(wrong_case)
        for key, value in (("startTime", "1900:01:01 00:00:00"), ("sortDirection", "asc"),
                           ("offset", "-1"), ("limit", "0"), ("limit", "oops")):
            params = self.params()
            params[key] = value
            variants.append(params)
        for params in variants:
            self.assertEqual((await self.client.get(CONTENT, params=params)).status, 400)
        params = self.list_params()
        params["sort_by"] = "date_time"
        self.assertEqual((await self.client.get(LIST, params=params)).status, 400)
        self.assertEqual((await self.client.get(CONTENT, params=self.params(album=1))).status, 404)

    async def test_failure_retries_preserve_same_offset_and_revocation_is_explicit(self):
        qa.set_shared_mode(self.state, "list-failure")
        self.assertEqual((await self.client.get(LIST, params=self.list_params())).status, 503)
        qa.set_shared_mode(self.state, "page-failure")
        self.assertEqual((await self.client.get(CONTENT, params=self.params())).status, 200)
        self.assertEqual((await self.client.get(CONTENT, params=self.params(50))).status, 503)
        qa.set_shared_mode(self.state, "normal")
        retry = await self.client.get(CONTENT, params=self.params(50))
        self.assertEqual((await retry.json())["data"]["list"][0]["id"], 10051)
        qa.set_shared_mode(self.state, "revoked")
        self.assertEqual((await self.client.get(CONTENT, params=self.params())).status, 403)
        self.assertEqual((await self.client.post("/__qa/shared-control")).status, 404)

    async def test_delayed_response_can_finish_after_new_navigation_request(self):
        qa.set_shared_mode(self.state, "normal", 150)
        pending = asyncio.create_task(self.client.get(LIST, params=self.list_params()))
        await asyncio.sleep(.03)
        gallery = await self.client.get("/p/api/v1/album/list")
        self.assertEqual(gallery.status, 200)
        self.assertFalse(pending.done())
        self.assertEqual((await pending).status, 200)
        # This establishes a fixture race, not an Android stale-response test.

    async def test_invalid_local_control_fails_closed(self):
        (self.state / "shared-mode.json").write_text('{"mode":"wrong","delay_ms":0}')
        self.assertEqual((await self.client.get(LIST, params=self.list_params())).status, 503)
        with self.assertRaises(ValueError):
            qa.set_shared_mode(self.state, "normal", -1)


if __name__ == "__main__":
    unittest.main()
