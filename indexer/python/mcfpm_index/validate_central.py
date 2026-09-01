from __future__ import annotations

import argparse
import json
from pathlib import Path

from .collectors import collect_central_full
from .worker import DEFAULT_CENTRAL_REPOSITORY


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Validate a Central full-index candidate snapshot")
    parser.add_argument("--input", required=True, type=Path)
    args = parser.parse_args(argv)
    candidates = collect_central_full(args.input, DEFAULT_CENTRAL_REPOSITORY)
    print(json.dumps({"ok": True, "candidates": len(candidates)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
