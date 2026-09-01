import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from mcfpm_index.descriptor import validate_descriptor
from mcfpm_index.model import Candidate, ValidationError
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

    def test_snapshot_sorts_semver_and_records_rejection(self):
        candidates = [candidate("central", "1.2.3"), candidate("nexus", "2.0.0-rc.1"), candidate("nexus", "2.0.0")]

        def load(value):
            if value.version == "2.0.0-rc.1":
                raise ValidationError("broken test descriptor")
            body = json.dumps(descriptor(value.version)).encode()
            return validate_descriptor(value, body, value.descriptor_url)

        snapshot = build_snapshot(
            candidates,
            source_status={"test": {"ok": True}},
            load_descriptor=load,
            generated_at="2026-09-02T00:00:00Z",
        )
        self.assertEqual("2.0.0", snapshot["packages"][0]["latestVersion"])
        self.assertEqual("reviewed", snapshot["packages"][0]["trust"])
        self.assertEqual(1, len(snapshot["rejected"]))

    def test_snapshot_write_is_valid_utf8_json(self):
        snapshot = {"schemaVersion": 1, "packages": [], "generatedAt": "now"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            write_snapshot_atomic(path, snapshot)
            self.assertEqual(snapshot, json.loads(path.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
