from __future__ import annotations

import json
import os
import tempfile
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional

from .descriptor import fetch_and_validate_descriptor
from .model import Candidate, semver_key


DescriptorLoader = Callable[[Candidate], Dict[str, Any]]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def build_snapshot(
    candidates: Iterable[Candidate],
    *,
    source_status: Dict[str, Any],
    load_descriptor: Optional[DescriptorLoader] = None,
    generated_at: Optional[str] = None,
) -> Dict[str, Any]:
    loader = load_descriptor or fetch_and_validate_descriptor
    versions_by_package: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    coordinates: Dict[str, tuple[str, str]] = {}
    rejected = []
    for candidate in candidates:
        try:
            version = loader(candidate)
        except Exception as exc:  # per-package upstream data must not abort other packages
            rejected.append({"gav": candidate.gav, "source": candidate.source, "error": str(exc)[:500]})
            continue
        versions_by_package[candidate.coordinate].append(version)
        coordinates[candidate.coordinate] = candidate.group, candidate.name

    packages = []
    for coordinate in sorted(versions_by_package):
        group, name = coordinates[coordinate]
        versions = sorted(versions_by_package[coordinate], key=lambda item: semver_key(item["version"]), reverse=True)
        latest = versions[0]
        packages.append(
            {
                "coordinate": coordinate,
                "group": group,
                "name": name,
                "latestVersion": latest["version"],
                "versionCount": len(versions),
                "trust": latest["trust"],
                "sources": sorted({version["source"] for version in versions}),
                "types": sorted({kind for version in versions for kind in version["types"]}),
                "licenses": sorted({version["license"] for version in versions}),
                "description": latest.get("description"),
                "versions": versions,
            }
        )
    return {
        "schemaVersion": 1,
        "generatedAt": generated_at or utc_now(),
        "packageCount": len(packages),
        "versionCount": sum(package["versionCount"] for package in packages),
        "sourceStatus": source_status,
        "rejected": sorted(rejected, key=lambda item: (item["gav"], item["source"])),
        "packages": packages,
    }


def write_snapshot_atomic(path: Path, snapshot: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(snapshot, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    descriptor, temporary_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "wb") as temporary:
            temporary.write(encoded)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, path)
    except Exception:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise
