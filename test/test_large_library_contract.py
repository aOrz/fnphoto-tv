"""Offline synthetic-contract tests, not evidence about a real NAS version."""
import asyncio
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
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
    async def asyncTearDown(self):
        import qa_server
        qa_server.configure_fixture("large")
        server.GEO_RESPONSE_LIMIT = None
        server.GEO_REPORT_PAGING = True

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

    async def test_album_and_gallery_failures_are_independent_and_retry_same_offset(self):
        middleware = fixture.fault_middleware(fail_once_offset=35, fail_once_album_offset=35)
        async def success(request): return "ok"
        for path in ("/p/api/v1/album/photos", "/p/api/v1/gallery/getList"):
            self.assertEqual(await middleware(SimpleNamespace(path=path, query={"offset": "0"}), success), "ok")
            request = SimpleNamespace(path=path, query={"offset": "35"})
            with self.assertRaises(web.HTTPServiceUnavailable): await middleware(request, success)
            self.assertEqual(await middleware(request, success), "ok")

    async def test_gallery_only_fault_does_not_apply_to_album(self):
        middleware = fixture.fault_middleware(fail_once_offset=35, gallery_delay_ms=3000)
        async def success(request): return "ok"
        with patch.object(fixture.asyncio, "sleep", new_callable=AsyncMock) as sleep:
            album = SimpleNamespace(path="/p/api/v1/album/photos", query={"offset": "35"})
            self.assertEqual(await middleware(album, success), "ok")
            sleep.assert_not_awaited()
            gallery = SimpleNamespace(path="/p/api/v1/gallery/getList", query={"offset": "35"})
            with self.assertRaises(web.HTTPServiceUnavailable): await middleware(gallery, success)
            sleep.assert_awaited_once_with(3)

    async def test_thumbnail_and_metadata_delays_are_independent(self):
        middleware = fixture.fault_middleware(gallery_delay_ms=3000, album_delay_ms=1000, thumbnail_delay_ms=9000)
        async def success(request): return "ok"
        cases = [("/p/api/v1/gallery/getList", {}, 3), ("/p/api/v1/album/photos", {}, 1),
                 ("/p/api/v1/stream/p/t/1/s/boundary", {}, 9),
                 ("/p/api/v1/stream/p/t/1/m/boundary", {}, 9),
                 ("/p/api/v1/stream/p/t/1/o/boundary", {}, None),
                 ("/p/api/v1/stream/p/t/1/o/boundary", {"size": "original"}, None),
                 ("/p/api/v1/stream/v/1", {}, None), ("/p/api/v1/stream/face/1", {}, None),
                 ("/p/api/v1/album/list", {}, None)]
        cases += [("/p/api/v1/stream/p/t/1/o/boundary", {"size": size}, 9) for size in ("m", "s", "xs", "xxs")]
        for path, query, delay in cases:
            with self.subTest(path=path, query=query), patch.object(fixture.asyncio, "sleep", new_callable=AsyncMock) as sleep:
                self.assertEqual(await middleware(SimpleNamespace(path=path, query=query), success), "ok")
                if delay is None: sleep.assert_not_awaited()
                else: sleep.assert_awaited_once_with(delay)

    async def test_zero_delays_do_not_schedule_sleep(self):
        middleware = fixture.fault_middleware()
        async def success(request): return "ok"
        with patch.object(fixture.asyncio, "sleep", new_callable=AsyncMock) as sleep:
            for path in ("/p/api/v1/gallery/getList", "/p/api/v1/album/photos", "/p/api/v1/stream/p/t/1/s/boundary"):
                self.assertEqual(await middleware(SimpleNamespace(path=path, query={}), success), "ok")
            sleep.assert_not_awaited()

    async def test_gallery_cap_and_unknown_paging_survive_delayed_failure_retry(self):
        fixture.configure_fixture("large-place", 20)
        middleware = fixture.fault_middleware(fail_once_offset=7, gallery_delay_ms=3000,
                                              gallery_page_cap=7, omit_gallery_paging=True, thumbnail_delay_ms=9000)
        request = SimpleNamespace(path="/p/api/v1/gallery/getList", query={"offset": "7", "limit": "35"})
        async def unexpected(request): self.fail("capped gallery response should be handled by middleware")
        with patch.object(fixture.asyncio, "sleep", new_callable=AsyncMock) as sleep:
            with self.assertRaises(web.HTTPServiceUnavailable): await middleware(request, unexpected)
            data = json.loads((await middleware(request, unexpected)).text)["data"]
            self.assertEqual(set(data), {"list"})
            self.assertEqual([p["id"] for p in data["list"]], list(range(13, 6, -1)))
            self.assertEqual(sleep.await_count, 2)
            self.assertTrue(all(call.args == (3,) for call in sleep.await_args_list))

    async def test_album_metadata_completes_while_thumbnail_http_response_is_pending(self):
        fixture.configure_fixture("large-place", 70)
        thumbnail_path = "/p/api/v1/stream/p/t/1/o/boundary"
        entered = asyncio.Event()
        @web.middleware
        async def observe(request, handler):
            if request.path == thumbnail_path: entered.set()
            return await handler(request)
        app = server.build_app()
        app.middlewares.extend([observe, fixture.fault_middleware(album_delay_ms=20, thumbnail_delay_ms=400)])
        async with TestClient(TestServer(app)) as client:
            image = asyncio.create_task(client.get(thumbnail_path, params={"size": "m"}))
            try:
                await asyncio.wait_for(entered.wait(), 2)
                metadata = await client.get("/p/api/v1/album/photos", params={
                    "album_id": server.ALBUMS[0]["albumId"], "offset": 35, "limit": 35})
                self.assertEqual(metadata.status, 200)
                self.assertEqual(len((await metadata.json())["data"]["list"]), 35)
                self.assertFalse(image.done(), "metadata must not wait for thumbnail delay")
                response = await asyncio.wait_for(image, 2)
                self.assertEqual(response.status, 200)
                self.assertEqual(response.content_type, "image/jpeg")
                self.assertTrue((await response.read()).startswith(b"\xff\xd8"))
            finally:
                if not image.done(): image.cancel()
                await asyncio.gather(image, return_exceptions=True)


class FaultArgumentTests(unittest.TestCase):
    def test_delay_limits_and_failure_offsets_are_validated_before_startup(self):
        invalid = [(name, value) for name in ("--thumbnail-delay-ms", "--album-delay-ms", "--gallery-delay-ms")
                   for value in ("-1", "10001")]
        invalid += [("--fail-once-offset", "-1"), ("--fail-once-album-offset", "-1")]
        for name, value in invalid:
            with self.subTest(name=name, value=value), patch.object(fixture, "configure_fixture") as configure, \
                    patch.object(fixture.web, "run_app") as run, redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error: fixture.main([name, value])
                self.assertEqual(error.exception.code, 2)
                configure.assert_not_called()
                run.assert_not_called()

    def test_zero_and_maximum_delays_are_accepted_and_forwarded(self):
        for delay in (0, 10000):
            with self.subTest(delay=delay), patch.object(fixture, "configure_fixture"), \
                    patch.object(fixture, "fault_middleware", wraps=fixture.fault_middleware) as middleware, \
                    patch.object(fixture.web, "run_app") as run, redirect_stdout(io.StringIO()):
                fixture.main(["--gallery-delay-ms", str(delay), "--album-delay-ms", str(delay),
                              "--thumbnail-delay-ms", str(delay), "--fail-once-album-offset", "0"])
                self.assertEqual(middleware.call_args.args[2], delay)
                self.assertEqual(middleware.call_args.kwargs, {
                    "album_delay_ms": delay, "thumbnail_delay_ms": delay, "fail_once_album_offset": 0})
                run.assert_called_once()


if __name__ == "__main__":
    unittest.main()
