#!/usr/bin/env python3
import argparse
import json
import urllib.request


tests = [
    ("/p/api/v1/photo/detail/1", "Photo Detail"),
    ("/p/api/v1/gallery/timeline", "Timeline"),
    ("/p/api/v1/album/list", "Album List"),
    ("/p/api/v1/photo/folder/list?desc=false&orderBy=2", "Folder List"),
    ("/p/api/v1/ai-person/list", "Person List"),
    ("/p/api/v1/explore/geos", "Geo List"),
    ("/p/api/v1/gallery/recent", "Recent"),
    ("/p/api/v1/photo/collect/list", "Collect List"),
]

# Test stream endpoints
stream_tests = [
    "/p/api/v1/stream/p/t/1/o/test_uuid?size=m",
    "/p/api/v1/stream/p/t/1/o/test_uuid",
    "/p/api/v1/stream/face/101",
    "/p/api/v1/stream/v/1",
]


def main():
    parser = argparse.ArgumentParser(description="Explicit network smoke test of an already running mock server")
    parser.add_argument("--host", default="http://127.0.0.1:5666", help="Mock base URL (never implicitly contacts a NAS)")
    args = parser.parse_args()
    host = args.host.rstrip("/")
    failures = 0
    for path, label in tests:
        try:
            req = urllib.request.Request(host + path)
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read())
            ok = data.get("code") == 0 or data.get("errno") == 0
            if not ok:
                failures += 1
            print(f"[{'OK' if ok else '??'}] {label}")
        except Exception as e:
            failures += 1
            print(f"[ERR] {label}: {e}")

    for path in stream_tests:
        try:
            req = urllib.request.Request(host + path)
            with urllib.request.urlopen(req, timeout=10) as resp:
                print(f"[OK] {path} => {resp.status} {resp.getheader('Content-Type')} ({len(resp.read())} bytes)")
        except Exception as e:
            failures += 1
            print(f"[ERR] {path}: {e}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
