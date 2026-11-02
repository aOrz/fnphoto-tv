"""Year/month synthetic fixture contracts. These do not verify real NAS semantics."""
from datetime import datetime
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import AsyncMock, patch

from aiohttp.test_utils import TestClient, TestServer
import qa_server
import test_server as mock
import year_month_server as fixture


def album_query(offset=0, album_id=7001, limit=35):
    return dict(album_id=str(album_id), sort_by="date_time", sort_direction="desc",
                offset=str(offset), limit=str(limit))


def month_query(month=9, source="gallery", offset=0, limit=35):
    end_day = {7: 31, 8: 31, 9: 30, 10: 31}[month]
    start, end = "2026:%02d:01 00:00:00" % month, "2026:%02d:%02d 23:59:59" % (month, end_day)
    query = dict(start_time=start, end_time=end, offset=str(offset), limit=str(limit))
    if source == "gallery":
        query["mode"] = "index"
    elif source == "person":
        query.update(personId="71", album_id="71", startTime=start, endTime=end)
    elif source == "shared":
        query = dict(albumId="9001", startTime=start, endTime=end, sortDirection="desc",
                     offset=str(offset), limit=str(limit))
    return query


class YearMonthContracts(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.state = Path(self.directory.name)
        self.client = TestClient(TestServer(fixture.build_app(self.state)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.directory.cleanup()
        # The existing mock is a process-local singleton used by other test modules.
        qa_server.configure_fixture("large")

    async def get_data(self, path, query):
        response = await self.client.get(path, params=query)
        self.assertEqual(response.status, 200, await response.text())
        return (await response.json())["data"]

    async def test_five_raw_pages_have_no_target_and_explicit_next_batch_can_find_it(self):
        scanned = 0
        for _ in range(5):
            data = await self.get_data(fixture.ALBUM_PATH, album_query(scanned))
            self.assertTrue(data["hasNext"])
            self.assertTrue(all(not p["photoDateTime"].startswith("2026-09") for p in data["list"]))
            scanned += len(data["list"])
        self.assertEqual(scanned, 175)
        sixth = await self.get_data(fixture.ALBUM_PATH, album_query(scanned))
        self.assertTrue(all(not p["photoDateTime"].startswith("2026-09") for p in sixth["list"]))
        seventh = await self.get_data(fixture.ALBUM_PATH, album_query(scanned + len(sixth["list"])))
        self.assertEqual(seventh["list"][0]["id"], 1211)
        self.assertTrue(all(p["photoDateTime"].startswith("2026-09") for p in seventh["list"]))
        self.assertFalse(seventh["hasNext"])

    async def test_capture_dates_are_not_monotonic_in_raw_date_time_order(self):
        data = await self.get_data(fixture.ALBUM_PATH, album_query())
        self.assertEqual([p["photoDateTime"][:7] for p in data["list"][:3]],
                         ["2026-10", "2026-08", "2026-10"])
        self.assertGreater(data["list"][0]["dateTime"], data["list"][1]["dateTime"])
        later = await self.get_data(fixture.ALBUM_PATH, album_query(210))
        self.assertTrue(later["list"][0]["photoDateTime"].startswith("2026-09"))

    async def test_ordinary_album_rejects_guessed_date_filters(self):
        query = album_query()
        query["start_time"] = "2026:09:01 00:00:00"
        response = await self.client.get(fixture.ALBUM_PATH, params=query)
        self.assertEqual(response.status, 400)

    async def test_gallery_month_query_filters_before_applying_its_own_offset(self):
        data = await self.get_data(fixture.GALLERY_PATH, month_query(offset=5, limit=4))
        self.assertEqual([p["id"] for p in data["list"]], [1216, 1217, 1218, 1219])
        self.assertTrue(data["hasNext"])
        self.assertTrue(all(p["photoDateTime"].startswith("2026-09") for p in data["list"]))
        # Source offset 5 and month offset 5 deliberately refer to different media.
        ordinary = await self.get_data(fixture.ALBUM_PATH, album_query(offset=5, limit=4))
        self.assertEqual(ordinary["list"][0]["id"], 1006)

    async def test_gallery_keeps_category_and_favorite_variation_for_local_filtering(self):
        data = await self.get_data(fixture.GALLERY_PATH, month_query())
        self.assertEqual({p["category"] for p in data["list"]}, {"photo", "video"})
        self.assertEqual({p["isCollect"] for p in data["list"]}, {0, 1})
        query = month_query()
        query["category"] = "photo"
        self.assertEqual((await self.client.get(fixture.GALLERY_PATH, params=query)).status, 400)

    async def test_person_query_preserves_person_and_all_existing_time_aliases(self):
        query = month_query(source="person")
        data = await self.get_data(fixture.PERSON_PATH, query)
        expected = [p["id"] for p in mock.PERSON_PHOTOS[71] if p["photoDateTime"].startswith("2026-09")]
        self.assertEqual([p["id"] for p in data["list"]], expected)
        query["personId"] = "72"
        self.assertEqual((await self.client.get(fixture.PERSON_PATH, params=query)).status, 400)
        query = month_query(source="person")
        query["startTime"] = "2026:10:01 00:00:00"
        self.assertEqual((await self.client.get(fixture.PERSON_PATH, params=query)).status, 400)

    async def test_shared_month_query_is_distinct_and_preserves_camel_case(self):
        data = await self.get_data(fixture.SHARED_PATH, month_query(source="shared", limit=50))
        self.assertEqual(len(data["list"]), 25)
        self.assertEqual(data["list"][0]["id"], 11061)
        self.assertFalse(data["hasNext"])
        self.assertTrue(all("additional" not in p for p in data["list"]))
        query = month_query(source="shared")
        query["album_id"] = query.pop("albumId")
        self.assertEqual((await self.client.get(fixture.SHARED_PATH, params=query)).status, 400)

    async def test_empty_month_has_explicit_end_in_each_verified_date_source(self):
        for path, source in ((fixture.GALLERY_PATH, "gallery"), (fixture.PERSON_PATH, "person"),
                             (fixture.SHARED_PATH, "shared")):
            data = await self.get_data(path, month_query(month=7, source=source))
            self.assertEqual(data["list"], [])
            self.assertFalse(data["hasNext"])
        data = await self.get_data(fixture.ALBUM_PATH, album_query(album_id=7003))
        self.assertEqual(data["list"], [])
        self.assertFalse(data["hasNext"])

    async def test_unknown_dates_are_available_with_and_without_valid_matches(self):
        mixed = await self.get_data(fixture.ALBUM_PATH, album_query(album_id=7002))
        unknown = await self.get_data(fixture.ALBUM_PATH, album_query(album_id=7004))
        self.assertEqual([p["id"] for p in mixed["list"]], [2001, 2002, 2003])
        self.assertEqual([p["id"] for p in unknown["list"]], [2001, 2002])
        self.assertIsNone(unknown["list"][0]["photoDateTime"])
        with self.assertRaises(ValueError):
            datetime.strptime(unknown["list"][1]["photoDateTime"], "%Y-%m-%d %H:%M:%S")
        self.assertTrue(all(p["dateTime"].startswith("2026-09") for p in unknown["list"]))
        self.assertFalse(unknown["hasNext"])

    async def test_short_pages_without_has_next_advance_by_raw_count_until_empty(self):
        await self.client.close()
        self.client = TestClient(TestServer(fixture.build_app(self.state, album_page_cap=7, omit_album_paging=True)))
        await self.client.start_server()
        offset, ids, target_offsets = 0, [], []
        while True:
            data = await self.get_data(fixture.ALBUM_PATH, album_query(offset))
            self.assertEqual(set(data), {"list"})
            self.assertLessEqual(len(data["list"]), 7)
            if not data["list"]:
                break
            if any(p["photoDateTime"].startswith("2026-09") for p in data["list"]):
                target_offsets.append(offset)
            ids.extend(p["id"] for p in data["list"])
            offset += len(data["list"])
        self.assertEqual(ids, list(range(1001, 1246)))
        self.assertEqual(target_offsets, [210, 217, 224, 231, 238])
        self.assertEqual(offset, 245)

    async def test_one_shot_failure_retries_the_same_offset(self):
        fixture.control_state(self.state, fail_once_offset=175)
        response = await self.client.get(fixture.ALBUM_PATH, params=album_query(175))
        self.assertEqual(response.status, 503)
        retried = await self.get_data(fixture.ALBUM_PATH, album_query(175))
        self.assertEqual(retried["list"][0]["id"], 1176)
        self.assertEqual(len(retried["list"]), 35)

    async def test_repeated_raw_page_is_exposed_without_fabricated_end(self):
        fixture.control_state(self.state, repeat_album_offset=35)
        first = await self.get_data(fixture.ALBUM_PATH, album_query())
        repeated = await self.get_data(fixture.ALBUM_PATH, album_query(35))
        self.assertEqual(first["list"], repeated["list"])
        self.assertTrue(repeated["hasNext"])

    async def test_deletion_moves_raw_coordinates_and_removes_stable_id(self):
        before = await self.get_data(fixture.ALBUM_PATH, album_query())
        self.assertEqual(before["list"][0]["id"], 1001)
        fixture.control_state(self.state, delete_id=1001)
        after = await self.get_data(fixture.ALBUM_PATH, album_query())
        self.assertEqual(after["list"][0]["id"], 1002)
        self.assertNotIn(1001, [p["id"] for p in after["list"]])
        gallery = await self.get_data(fixture.GALLERY_PATH, month_query(month=10))
        self.assertNotIn(1001, [p["id"] for p in gallery["list"]])

    async def test_delay_is_controllable_without_delaying_media_or_login(self):
        fixture.control_state(self.state, delay_ms=2500)
        with patch.object(fixture.asyncio, "sleep", new_callable=AsyncMock) as sleep:
            await self.get_data(fixture.ALBUM_PATH, album_query())
            sleep.assert_awaited_once_with(2.5)
            sleep.reset_mock()
            response = await self.client.get("/p/api/v1/app/version")
            self.assertEqual(response.status, 200)
            sleep.assert_not_awaited()

    async def test_invalid_state_is_explicit_service_failure_until_local_reset(self):
        (self.state / "year-month.json").write_text('{"delay_ms": "fast"}', encoding="utf-8")
        response = await self.client.get(fixture.ALBUM_PATH, params=album_query())
        self.assertEqual(response.status, 503)
        fixture.control_state(self.state)
        data = await self.get_data(fixture.ALBUM_PATH, album_query())
        self.assertEqual(data["list"][0]["id"], 1001)

    async def test_trace_records_query_coordinates_without_headers_or_unknown_fields(self):
        await self.client.close()
        trace_path = self.state / "requests.jsonl"
        self.client = TestClient(TestServer(fixture.build_app(self.state, trace_file=trace_path)))
        await self.client.start_server()
        response = await self.client.get(fixture.GALLERY_PATH, params=month_query(offset=5),
                                         headers={"accesstoken": "synthetic-trace-secret"})
        self.assertEqual(response.status, 200)
        text = trace_path.read_text(encoding="utf-8")
        event = json.loads(text)
        self.assertEqual(event["offset"], 5)
        self.assertEqual(event["start_time"], "2026:09:01 00:00:00")
        self.assertNotIn("synthetic-trace-secret", text)
        self.assertNotIn("accesstoken", text)


if __name__ == "__main__":
    unittest.main()
