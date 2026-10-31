"""Offline handler tests: python -m unittest discover -s test -p 'test_mock_contract.py'.

No NAS, listening socket, image rendering, ffmpeg, or Android device is needed.
"""
import asyncio
import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from aiohttp import web
import test_server as server


def request(**query):
    return SimpleNamespace(query={key: str(value) for key, value in query.items()})


def data(handler, **query):
    return json.loads(asyncio.run(handler(request(**query))).text)["data"]


def photos(count):
    template = server.PHOTOS[0]
    return [dict(template, id=i, fileName="match_%05d.jpg" % i,
                 dateTime="2026-01-02 12:00:00", photoDateTime="2026-01-02 12:00:00")
            for i in range(count)]


class MockContractTests(unittest.TestCase):
    def test_album_offset_contract_full_traversal(self):
        for count in (0, 34, 35, 36, 100, 1000, 10000):
            with self.subTest(count=count), patch.object(server, "ALBUM_MAP", {9: {"photos": photos(count)}}):
                ids, offset, requests = [], 0, 0
                while True:
                    result = data(server.handle_album_photos, album_id=9, offset=offset, limit=35)
                    requests += 1
                    self.assertEqual(result["total"], count)
                    self.assertEqual(result["count"], len(result["list"]))
                    self.assertLessEqual(result["count"], 35)
                    ids.extend(p["id"] for p in result["list"])
                    offset += len(result["list"])
                    self.assertEqual(result["hasNext"], offset < count)
                    if not result["hasNext"]:
                        break
                    self.assertLess(requests, 300)
                self.assertEqual(ids, list(reversed(range(count))))
                self.assertEqual(requests, max(1, (count + 34) // 35))

    def test_album_directory_pagination(self):
        albums = [{"albumId": i} for i in range(1001)]
        with patch.object(server, "ALBUMS", albums):
            first = data(server.handle_album_list, offset=0, limit=1000)
            last = data(server.handle_album_list, offset=1000, limit=1000)
            self.assertTrue(first["hasNext"])
            self.assertEqual(last["list"], [{"albumId": 1000}])
            self.assertFalse(last["hasNext"])

    def test_legacy_alias_and_native_precedence(self):
        self.assertEqual(server.extract_paging(request(page=3, pageSize=35)), (70, 35))
        self.assertEqual(server.extract_paging(request(page=9, pageSize=99, offset=35, limit=35)), (35, 35))
        with patch.object(server, "ALBUM_MAP", {9: {"photos": photos(100)}}):
            native = data(server.handle_album_photos, album_id=9, offset=35, limit=35)
            legacy = data(server.handle_album_photos, albumId=9, page=2, pageSize=35)
            self.assertEqual(native, legacy)

    def test_empty_and_past_end_and_unlimited(self):
        for offset, limit, expected in ((100, 35, []), (35, -1, list(range(35, 80)))):
            result, total = server.paginate(list(range(80)), offset, limit)
            self.assertEqual(result, expected)
            self.assertEqual(total, 80)
        self.assertEqual(server.make_list([], total=0)["total"], 0)

    def test_invalid_pagination_is_400(self):
        for values in ({"offset": -1}, {"limit": 0}, {"limit": -2}, {"limit": "bad"}, {"page": 0}):
            with self.subTest(values=values), self.assertRaises(web.HTTPBadRequest):
                server.extract_paging(request(**values))

    def test_date_filter_uses_real_photo_date_and_inclusive_boundaries(self):
        sample = photos(4)
        for item, date in zip(sample, ("2026-01-01 23:59:59", "2026-01-02 00:00:00", "2026-01-02 23:59:59", "2026-01-03 00:00:00")):
            item["photoDateTime"] = date
        with patch.object(server, "PHOTOS", sample):
            result = data(server.handle_gallery_list, start_time="2026:01:02 00:00:00", end_time="2026:01:02 23:59:59", offset=0, limit=1)
            self.assertEqual([p["id"] for p in result["list"]], [2])
            self.assertTrue(result["hasNext"])
            self.assertEqual(result["total"], 2)
            second = data(server.handle_gallery_list, startTime="2026-01-02 00:00:00", endTime="2026-01-02 23:59:59", offset=1, limit=1)
            self.assertEqual([p["id"] for p in second["list"]], [1])
            self.assertFalse(second["hasNext"])

    def test_legacy_epoch_date_and_invalid_date(self):
        self.assertEqual(server.parse_photo_time("1767312000000"), server.parse_photo_time("2026:01:02 00:00:00"))
        with self.assertRaises(web.HTTPBadRequest):
            server.filter_dates(request(start_time="not-a-date"), photos(1))

    def test_person_date_paging_and_timeline_id(self):
        sample = photos(36)
        sample[0]["photoDateTime"] = "2026-01-03 00:00:00"
        with patch.object(server, "PERSON_PHOTOS", {2: sample}):
            result = data(server.handle_person_photos, personId=2, start_time="2026:01:02 00:00:00", end_time="2026:01:02 23:59:59", limit=35, offset=0)
            self.assertEqual(result["total"], 35)
            self.assertFalse(result["hasNext"])
            timeline = data(server.handle_person_timeline, id=2)
            self.assertEqual([p["itemCount"] for p in timeline["list"]], [1, 35])

    def test_search_paging_not_capped_to_default_subset(self):
        with patch.object(server, "PHOTOS", photos(201)):
            first = data(server.handle_search, keyword="MATCH", limit=100, offset=0)
            last = data(server.handle_search, keyword="match", limit=100, offset=200)
            self.assertEqual(first["total"], 201)
            self.assertTrue(first["hasNext"])
            self.assertEqual(last["count"], 1)
            self.assertFalse(last["hasNext"])
            self.assertEqual(data(server.handle_search, keyword="absent", limit=100, offset=0)["total"], 0)
            self.assertEqual(data(server.handle_search, keyword="", limit=100, offset=0)["total"], 201)

    def test_folder_native_path_and_multiple_pages(self):
        with patch.object(server, "PHOTOS", photos(201)):
            result = data(server.handle_folder_file_list, folderPath="/volume1/photo/Test", limit=100, offset=100)
            self.assertEqual(result["count"], 100)
            self.assertEqual(result["list"][0]["id"], 100)
            self.assertTrue(result["hasNext"])
            self.assertTrue(result["list"][0]["filePath"].startswith("/volume1/photo/Test/"))
            last = data(server.handle_folder_file_list, limit=100, offset=200)
            self.assertEqual(last["count"], 1)
            self.assertFalse(last["hasNext"])
        folders = data(server.handle_sub_folder_list, folderPath="/volume1/photo/Test")
        self.assertEqual(folders["list"][0]["path"], "/volume1/photo/Test/Subfolder1")

    def test_recent_and_collect_support_offset(self):
        sample = photos(101)
        for p in sample:
            p["isCollect"] = 1
        with patch.object(server, "PHOTOS", sample):
            for handler in (server.handle_recent, server.handle_collect_list):
                result = data(handler, limit=100, offset=100)
                self.assertEqual(result["total"], 101)
                self.assertEqual(result["count"], 1)
                self.assertFalse(result["hasNext"])

    def test_generated_timeline_matches_gallery_dates(self):
        for day in server.TIMELINE_ITEMS:
            date = "%(year)04d:%(month)02d:%(day)02d" % day
            result = data(server.handle_gallery_list, start_time=date + " 00:00:00", end_time=date + " 23:59:59", limit=-1)
            self.assertEqual(result["total"], day["itemCount"])

    def test_folder_thumbnail_route_is_registered(self):
        routes = [route.resource.canonical for route in server.build_app().router.routes()]
        self.assertIn("/p/api/v1/stream/p/t/{id}/s/{uuid}", routes)


if __name__ == "__main__":
    unittest.main()
