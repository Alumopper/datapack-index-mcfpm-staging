from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .collectors import (
    collect_central_full,
    collect_central_search,
    collect_nexus,
    deduplicate_candidates,
)
from .snapshot import build_snapshot, utc_now, write_snapshot_atomic


DEFAULT_NEXUS_SEARCH = (
    "https://nexus.mcfpp.top/service/rest/v1/search/assets"
    "?repository=maven-releases&maven.extension=mcfpkg"
)
DEFAULT_NEXUS_REPOSITORY = "https://nexus.mcfpp.top/repository/maven-releases/"
DEFAULT_CENTRAL_SEARCH = "https://search.maven.org/solrsearch/select"
DEFAULT_CENTRAL_REPOSITORY = "https://repo.maven.apache.org/maven2/"


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Build the Mcfpm Nexus + Maven Central package snapshot")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--central-full", type=Path)
    parser.add_argument("--nexus-search-url", default=DEFAULT_NEXUS_SEARCH)
    parser.add_argument("--nexus-repository-url", default=DEFAULT_NEXUS_REPOSITORY)
    parser.add_argument("--central-search-url", default=DEFAULT_CENTRAL_SEARCH)
    parser.add_argument("--central-repository-url", default=DEFAULT_CENTRAL_REPOSITORY)
    return parser.parse_args(argv)


def run(args) -> dict:
    started_at = utc_now()
    nexus = collect_nexus(args.nexus_search_url, args.nexus_repository_url)
    central_search = collect_central_search(args.central_search_url, args.central_repository_url)
    central_full = collect_central_full(args.central_full, args.central_repository_url)
    candidates = deduplicate_candidates([*nexus, *central_search, *central_full])
    status = {
        "nexus": {"ok": True, "discovered": len(nexus), "checkedAt": started_at},
        "centralSearch": {"ok": True, "discovered": len(central_search), "checkedAt": started_at},
        "centralFull": {
            "ok": True,
            "available": args.central_full is not None and args.central_full.exists(),
            "discovered": len(central_full),
            "checkedAt": started_at,
        },
    }
    snapshot = build_snapshot(candidates, source_status=status, generated_at=started_at)
    write_snapshot_atomic(args.output, snapshot)
    return snapshot


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        snapshot = run(args)
    except Exception as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(
        json.dumps(
            {
                "ok": True,
                "output": str(args.output),
                "packages": snapshot["packageCount"],
                "versions": snapshot["versionCount"],
                "rejected": len(snapshot["rejected"]),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
