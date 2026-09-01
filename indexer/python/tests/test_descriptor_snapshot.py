import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from mcfpm_index.descriptor import fetch_and_validate_descriptor, validate_descriptor
from mcfpm_index.http import NotFoundError
from mcfpm_index.model import Candidate, ValidationError
from mcfpm_index.site_metadata import site_metadata_url, validate_site_metadata
from mcfpm_index.snapshot import build_snapshot, write_snapshot_atomic


def candidate(source="central", version="1.2.3"):
    repository = "https://repo.example/"
    return Candidate(
        "org.example",
        "demo",
        version,
        source,
        repository,
        repository + f"org/example/demo/{version}/demo-{version}.mcfpkg",
        "test",
    )


def descriptor(version="1.2.3"):
    return {
        "schema": 1,
        "packageId": "org.example:demo",
        "version": version,
        "license": "MIT",
        "dependencies": ["org.example:base@^1.0.0"],
        "artifacts": [
            {
                "type": "minecraft.datapack",
                "classifier": "datapack",
                "requires": ["minecraft >=1.21"],
                "source": {"uri": "https://github.com/example/demo"},
            }
        ],
    }


def site_metadata(version="1.2.3"):
    return {
        "schema": 1,
        "packageId": "org.example:demo",
        "version": version,
        "name": "Demo library",
        "description": "Display description",
        "coverUrl": "https://example.test/cover.png",
        "authors": [
            {
                "name": "Example",
                "avatarUrl": "https://example.test/avatar.png",
                "links": [{"label": "GitHub", "url": "https://github.com/example"}],
            }
        ],
        "tags": ["utility"],
        "gameVersions": ["1.21"],
        "dependencyNotes": None,
        "detailsMarkdown": "## Usage\r\n\r\nDetails.",
        "projectUrl": "https://github.com/example/demo",
        "legacyPath": "/wheel/resources/demo.html",
    }


class DescriptorAndSnapshotTests(unittest.TestCase):
    def test_descriptor_is_bound_to_coordinate_and_hashed(self):
        body = json.dumps(descriptor(), separators=(",", ":")).encode()
        result = validate_descriptor(candidate(), body, candidate().descriptor_url)
        self.assertEqual(hashlib.sha256(body).hexdigest(), result["descriptorSha256"])
        self.assertEqual("community", result["trust"])
        self.assertEqual(["minecraft.datapack"], result["types"])

    def test_descriptor_coordinate_mismatch_is_rejected(self):
        value = descriptor()
        value["packageId"] = "org.example:other"
        with self.assertRaises(ValidationError):
            validate_descriptor(candidate(), json.dumps(value).encode(), candidate().descriptor_url)

    def test_site_metadata_is_bounded_and_bound_to_coordinate(self):
        value = site_metadata()
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
        result = validate_site_metadata(candidate("nexus"), body, site_metadata_url(candidate("nexus")))
        self.assertEqual("Demo library", result["name"])
        self.assertEqual(hashlib.sha256(body).hexdigest(), result["metadataSha256"])
        value["packageId"] = "org.example:other"
        with self.assertRaises(ValidationError):
            validate_site_metadata(
                candidate("nexus"),
                json.dumps(value).encode(),
                site_metadata_url(candidate("nexus")),
            )

        value = site_metadata()
        value["unexpected"] = "not part of the frozen schema"
        with self.assertRaises(ValidationError):
            validate_site_metadata(
                candidate("nexus"),
                json.dumps(value).encode(),
                site_metadata_url(candidate("nexus")),
            )

    def test_nexus_descriptor_loads_optional_site_metadata(self):
        value = candidate("nexus")
        descriptor_body = json.dumps(descriptor()).encode()
        metadata_body = json.dumps(site_metadata()).encode()

        def load(url):
            if url.endswith(".mcfpkg"):
                return descriptor_body, url
            return metadata_body, url

        result = fetch_and_validate_descriptor(value, get_bytes=load)
        self.assertEqual("Demo library", result["site"]["name"])

        def load_without_site(url):
            if url.endswith(".mcfpkg"):
                return descriptor_body, url
            raise NotFoundError("missing")

        result_without_site = fetch_and_validate_descriptor(value, get_bytes=load_without_site)
        self.assertIsNone(result_without_site["site"])

    def test_snapshot_sorts_semver_and_records_rejection(self):
        candidates = [candidate("central", "1.2.3"), candidate("nexus", "2.0.0-rc.1"), candidate("nexus", "2.0.0")]

        def load(value):
            if value.version == "2.0.0-rc.1":
                raise ValidationError("broken test descriptor")
            body = json.dumps(descriptor(value.version)).encode()
            result = validate_descriptor(value, body, value.descriptor_url)
            result["site"] = validate_site_metadata(
                value,
                json.dumps(site_metadata(value.version)).encode(),
                site_metadata_url(value),
            ) if value.source == "nexus" else None
            return result

        snapshot = build_snapshot(
            candidates,
            source_status={"test": {"ok": True}},
            load_descriptor=load,
            generated_at="2026-09-02T00:00:00Z",
        )
        self.assertEqual("2.0.0", snapshot["packages"][0]["latestVersion"])
        self.assertEqual("reviewed", snapshot["packages"][0]["trust"])
        self.assertEqual("Demo library", snapshot["packages"][0]["display"]["name"])
        self.assertEqual(1, len(snapshot["rejected"]))

    def test_snapshot_write_is_valid_utf8_json(self):
        snapshot = {"schemaVersion": 1, "packages": [], "generatedAt": "now"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            write_snapshot_atomic(path, snapshot)
            self.assertEqual(snapshot, json.loads(path.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
