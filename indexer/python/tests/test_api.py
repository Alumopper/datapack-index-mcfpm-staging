import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from mcfpm_index.api import SnapshotStore, ThreadingHTTPServer, make_handler


def version(name, source, trust, requirement):
    return {
        "version": name,
        "source": source,
        "trust": trust,
        "license": "MIT",
        "types": ["minecraft.datapack"],
        "minecraftRequirements": [requirement],
    }


def package(group, name, source, trust, requirement):
    item_version = version("1.0.0", source, trust, requirement)
    return {
        "coordinate": f"{group}:{name}",
        "group": group,
        "name": name,
        "latestVersion": "1.0.0",
        "versionCount": 1,
        "trust": trust,
        "sources": [source],
        "types": ["minecraft.datapack"],
        "licenses": ["MIT"],
        "minecraftRequirements": [requirement],
        "description": None,
        "display": {
            "name": f"Display {name}",
            "description": "A displayed package",
            "coverUrl": None,
            "authors": [{"name": "Example", "avatarUrl": None, "links": []}],
            "tags": ["utility"],
            "gameVersions": ["1.21"],
            "projectUrl": None,
            "legacyPath": None,
        },
        "versions": [item_version],
    }


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        path = Path(self.temporary.name) / "snapshot.json"
        snapshot = {
            "schemaVersion": 1,
            "generatedAt": "2026-09-02T00:00:00Z",
            "packageCount": 2,
            "versionCount": 2,
            "sourceStatus": {"nexus": {"ok": True}},
            "rejected": [],
            "packages": [
                package("a.example", "alpha", "central", "community", "minecraft >=1.20"),
                package("b.example", "beta", "nexus", "reviewed", "minecraft >=1.21"),
            ],
        }
        path.write_text(json.dumps(snapshot), encoding="utf-8")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(SnapshotStore(path)))
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def get(self, path, headers=None):
        with urlopen(Request(self.base + path, headers=headers or {}), timeout=2) as response:
            return response, json.loads(response.read())

    def test_list_pagination_and_cursor(self):
        response, first = self.get("/v1/packages?limit=1")
        self.assertEqual(2, first["total"])
        self.assertEqual("a.example:alpha", first["items"][0]["coordinate"])
        self.assertEqual(["minecraft >=1.20"], first["items"][0]["minecraftRequirements"])
        self.assertNotIn("versions", first["items"][0])
        _, second = self.get("/v1/packages?limit=1&cursor=" + first["nextCursor"])
        self.assertEqual("b.example:beta", second["items"][0]["coordinate"])
        self.assertEqual("*", response.headers["Access-Control-Allow-Origin"])

    def test_filters_and_details(self):
        _, filtered = self.get("/v1/packages?source=nexus&trust=reviewed&minecraft=1.21")
        self.assertEqual(["b.example:beta"], [item["coordinate"] for item in filtered["items"]])
        _, detail = self.get("/v1/packages/b.example/beta/1.0.0")
        self.assertEqual("nexus", detail["source"])

    def test_display_metadata_is_in_list_summary(self):
        _, payload = self.get("/v1/packages?q=Display%20beta")
        self.assertEqual("Display beta", payload["items"][0]["display"]["name"])

    def test_etag_returns_not_modified(self):
        response, _ = self.get("/v1/status")
        etag = response.headers["ETag"]
        with self.assertRaises(HTTPError) as caught:
            urlopen(Request(self.base + "/v1/status", headers={"If-None-Match": etag}), timeout=2)
        self.assertEqual(304, caught.exception.code)

    def test_invalid_cursor_is_safe_client_error(self):
        with self.assertRaises(HTTPError) as caught:
            urlopen(self.base + "/v1/packages?cursor=not-valid", timeout=2)
        self.assertEqual(400, caught.exception.code)


if __name__ == "__main__":
    unittest.main()
