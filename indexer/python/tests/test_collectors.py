import json
import tempfile
import unittest
from pathlib import Path

from mcfpm_index.collectors import (
    collect_central_full,
    collect_central_search,
    collect_nexus,
    deduplicate_candidates,
)
from mcfpm_index.model import Candidate, ValidationError


NEXUS_REPOSITORY = "https://nexus.example/repository/releases/"
CENTRAL_REPOSITORY = "https://repo.maven.apache.org/maven2/"


class CollectorTests(unittest.TestCase):
    def test_snapshot_version_is_rejected(self):
        with self.assertRaises(ValidationError):
            Candidate(
                "org.example",
                "demo",
                "1.0.0-SNAPSHOT",
                "central",
                CENTRAL_REPOSITORY,
                CENTRAL_REPOSITORY + "org/example/demo/1.0.0-SNAPSHOT/demo-1.0.0-SNAPSHOT.mcfpkg",
                "central-search",
            )

    def test_nexus_uses_canonical_repository_base_and_paginates(self):
        responses = [
            {
                "items": [
                    {
                        "downloadUrl": "https://stale.invalid/wrong",
                        "path": "/org/example/demo/1.2.3/demo-1.2.3.mcfpkg",
                        "maven2": {
                            "groupId": "org.example",
                            "artifactId": "demo",
                            "version": "1.2.3",
                            "extension": "mcfpkg",
                        },
                    }
                ],
                "continuationToken": "next token",
            },
            {"items": [], "continuationToken": None},
        ]
        seen = []

        def get_json(url):
            seen.append(url)
            return responses.pop(0)

        result = collect_nexus("https://nexus.example/search?x=1", NEXUS_REPOSITORY, get_json=get_json)
        self.assertEqual(1, len(result))
        self.assertEqual(
            "https://nexus.example/repository/releases/org/example/demo/1.2.3/demo-1.2.3.mcfpkg",
            result[0].descriptor_url,
        )
        self.assertIn("continuationToken=next+token", seen[1])

    def test_nexus_rejects_coordinate_path_mismatch(self):
        payload = {
            "items": [
                {
                    "path": "/org/example/other/1.2.3/other-1.2.3.mcfpkg",
                    "maven2": {
                        "groupId": "org.example",
                        "artifactId": "demo",
                        "version": "1.2.3",
                        "extension": "mcfpkg",
                    },
                }
            ],
            "continuationToken": None,
        }
        with self.assertRaises(ValidationError):
            collect_nexus("https://nexus.example/search", NEXUS_REPOSITORY, get_json=lambda _: payload)

    def test_nexus_accepts_decoded_semver_build_metadata_in_asset_path(self):
        payload = {
            "items": [
                {
                    "path": "/org/example/demo/2.0.2+0/demo-2.0.2+0.mcfpkg",
                    "maven2": {
                        "groupId": "org.example",
                        "artifactId": "demo",
                        "version": "2.0.2+0",
                        "extension": "mcfpkg",
                    },
                }
            ],
            "continuationToken": None,
        }
        result = collect_nexus("https://nexus.example/search", NEXUS_REPOSITORY, get_json=lambda _: payload)
        self.assertEqual("org.example:demo:2.0.2+0", result[0].gav)
        self.assertIn("2.0.2%2B0", result[0].descriptor_url)

    def test_central_search_paginates_to_num_found(self):
        pages = [
            {"response": {"numFound": 2, "docs": [{"g": "a.b", "a": "one", "v": "1.0.0", "p": "mcfpkg"}]}},
            {"response": {"numFound": 2, "docs": [{"g": "a.b", "a": "two", "v": "2.0.0", "p": "mcfpkg"}]}},
        ]
        result = collect_central_search(
            "https://search.example/select", CENTRAL_REPOSITORY, get_json=lambda _: pages.pop(0), rows=1
        )
        self.assertEqual(["a.b:one:1.0.0", "a.b:two:2.0.0"], [candidate.gav for candidate in result])

    def test_full_index_file_and_deduplication_prefer_nexus(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "central.json"
            path.write_text(
                json.dumps({"candidates": [{"group": "a.b", "name": "demo", "version": "1.0.0"}]}),
                encoding="utf-8",
            )
            central = collect_central_full(path, CENTRAL_REPOSITORY)[0]
        nexus = Candidate(
            "a.b",
            "demo",
            "1.0.0",
            "nexus",
            NEXUS_REPOSITORY,
            NEXUS_REPOSITORY + "a/b/demo/1.0.0/demo-1.0.0.mcfpkg",
            "nexus-search",
        )
        self.assertEqual([nexus], deduplicate_candidates([central, nexus]))


if __name__ == "__main__":
    unittest.main()
