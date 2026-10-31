"""Offline synthetic-contract tests, not evidence about a real NAS version."""
import asyncio
import json
import unittest
from unittest.mock import patch
from aiohttp import web
import test_server as server
import large_library_server as fixture


class JsonRequest:
    def __init__(self, body): self.body = body
    async def json(self): return self.body


def body(city="Boundary City", country="Synthetic Country"):
    return {"keyword": "", "filters": [{"filterName": "photo_location", "filterValue": country,
             "subFilters": [{"filterName": country, "filterValue": city}]}], "antiFilters": []}


def location(request_body=None):
    response = asyncio.run(server.handle_search_results(JsonRequest(body() if request_body is None else request_body)))
    return json.loads(response.text)["data"]


class LargeLibraryContracts(unittest.TestCase):
    def test_small_sparse_fixture_supports_budget_resume_device_check(self):
        fixture.configure_fixture("sparse-video", 200)
        ordered = sorted(server.PHOTOS, key=lambda p: p["id"], reverse=True)
        self.assertTrue(all(p["category"] == "photo" for p in ordered[:175]))
        self.assertEqual(ordered[-1]["category"], "video")

    def test_server_capped_short_pages_keep_raw_offsets_and_find_last_video(self):
        from types import SimpleNamespace
        fixture.configure_fixture("sparse-video", 20)
        ids, video_offsets, offset, offsets = [], [], 0, []
        while True:
            request = SimpleNamespace(query={"offset": str(offset), "limit": "35"})
            response = fixture.gallery_boundary_response(request, gallery_page_cap=7, omit_gallery_paging=True)
            data = json.loads(response.text)["data"]
            self.assertEqual(set(data), {"list"})
            self.assertLessEqual(len(data["list"]), 7)
            offsets.append(offset)
            ids.extend(photo["id"] for photo in data["list"])
            if any(photo["category"] == "video" for photo in data["list"]): video_offsets.append(offset)
            if not data["list"]: break
            offset += len(data["list"])
        self.assertEqual(offsets, [0, 7, 14, 20])
        self.assertEqual(ids, list(range(20, 0, -1)))
        self.assertEqual(video_offsets, [14])

    def test_server_page_cap_preserves_requested_smaller_limit_and_known_metadata(self):
        from types import SimpleNamespace
        fixture.configure_fixture("sparse-video", 20)
        request = SimpleNamespace(query={"offset": "7", "limit": "3"})
        data = json.loads(fixture.gallery_boundary_response(request, gallery_page_cap=7).text)["data"]
        self.assertEqual([p["id"] for p in data["list"]], [13, 12, 11])
        self.assertEqual(data["count"], 3)
        self.assertEqual(data["total"], 20)
        self.assertTrue(data["hasNext"])

    def tearDown(self):
        # Other test modules share the mock singleton; do not leave fixture switches behind.
        import qa_server
        qa_server.configure_fixture("large")
        server.GEO_RESPONSE_LIMIT = None
        server.GEO_REPORT_PAGING = True

    def test_location_filter_does_not_return_unrelated_first_twenty(self):
        photos = [{"id": i, "fileName": "a.jpg", "geo": "Boundary City, Synthetic Country"} for i in range(1, 51)]
        photos += [{"id": 99, "fileName": "a.jpg", "geo": "Elsewhere, Synthetic Country"}]
        with patch.object(server, "PHOTOS", photos):
            result = location()
            self.assertEqual(len(result["list"]), 50)
            self.assertEqual(result["total"], 50)
            self.assertFalse(result["hasNext"])
            self.assertNotIn(99, [p["id"] for p in result["list"]])
            self.assertEqual(location(body("missing"))["list"], [])

    def test_unknown_filter_does_not_silently_return_unfiltered_results(self):
        request_body = body()
        request_body["filters"][0]["filterName"] = "invented_filter"
        with self.assertRaises(web.HTTPBadRequest): location(request_body)

    def test_quote_names_are_treated_literally(self):
        city, country = 'A"City', 'B\\Country'
        with patch.object(server, "PHOTOS", [{"id": 1, "fileName": "a.jpg", "geo": city + ", " + country}]):
            self.assertEqual(location(body(city, country))["total"], 1)

    def test_large_place_exceeds_client_item_cap_without_lying_about_response_size(self):
        fixture.configure_fixture("large-place", 1201)
        result = location()
        self.assertEqual(len(result["list"]), 1201)
        self.assertEqual(result["total"], 1201)

    def test_incomplete_place_reports_more_but_does_not_invent_continuation_parameters(self):
        fixture.configure_fixture("place-incomplete", 1201)
        result = location()
        self.assertEqual(len(result["list"]), 35)
        self.assertTrue(result["hasNext"])
        self.assertEqual(result["total"], 1201)
        request_body = body()
        request_body.update(offset=35, limit=35)
        self.assertEqual(location(request_body), result)  # Unverified params deliberately do not paginate.

    def test_unknown_place_omits_unproven_completeness_metadata(self):
        fixture.configure_fixture("place-unknown", 1201)
        result = location()
        self.assertEqual(len(result["list"]), 35)
        self.assertNotIn("hasNext", result)
        self.assertNotIn("total", result)

    def test_sparse_video_is_after_many_no_match_pages(self):
        fixture.configure_fixture("sparse-video", 10000)
        ordered = sorted(server.PHOTOS, key=lambda p: p["id"], reverse=True)
        self.assertTrue(all(p["category"] == "photo" for p in ordered[:9999]))
        self.assertEqual(ordered[-1]["category"], "video")
        self.assertEqual(ordered[-1]["id"], 1)


class FaultScenarioTests(unittest.IsolatedAsyncioTestCase):
    async def test_gallery_failure_recovers_at_same_offset(self):
        from types import SimpleNamespace
        middleware = fixture.fault_middleware(fail_once_offset=35)
        request = SimpleNamespace(path="/p/api/v1/gallery/getList", query={"offset": "35"})
        async def success(request): return "ok"
        with self.assertRaises(web.HTTPServiceUnavailable): await middleware(request, success)
        self.assertEqual(await middleware(request, success), "ok")

    async def test_other_offset_does_not_consume_one_shot_failure(self):
        from types import SimpleNamespace
        middleware = fixture.fault_middleware(fail_once_offset=35)
        async def success(request): return "ok"
        self.assertEqual(await middleware(SimpleNamespace(path="/p/api/v1/gallery/getList", query={"offset": "0"}), success), "ok")
        with self.assertRaises(web.HTTPServiceUnavailable):
            await middleware(SimpleNamespace(path="/p/api/v1/gallery/getList", query={"offset": "35"}), success)

    async def test_location_failure_recovers_without_restarting_server(self):
        from types import SimpleNamespace
        middleware = fixture.fault_middleware(fail_first_location=True)
        request = SimpleNamespace(path="/p/api/v1/search/results", query={})
        async def success(request): return "ok"
        with self.assertRaises(web.HTTPServiceUnavailable): await middleware(request, success)
        self.assertEqual(await middleware(request, success), "ok")


if __name__ == "__main__":
    unittest.main()
