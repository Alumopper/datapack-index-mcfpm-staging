from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, Optional, Tuple
from urllib.parse import parse_qs, unquote, urlsplit

from .model import GROUP_RE, NAME_RE, SEMVER_RE


MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024
MAX_LIMIT = 100


class ApiError(ValueError):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class SnapshotStore:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        self._signature: Optional[Tuple[int, int]] = None
        self._snapshot: Optional[Dict[str, Any]] = None
        self._etag: Optional[str] = None
        self.last_error: Optional[str] = None

    def get(self) -> Tuple[Dict[str, Any], str]:
        try:
            stat = self.path.stat()
            signature = stat.st_mtime_ns, stat.st_size
        except OSError as exc:
            if self._snapshot is not None and self._etag is not None:
                self.last_error = str(exc)
                return self._snapshot, self._etag
            raise ApiError(HTTPStatus.SERVICE_UNAVAILABLE, "package snapshot is unavailable") from exc
        if signature == self._signature and self._snapshot is not None and self._etag is not None:
            return self._snapshot, self._etag
        with self._lock:
            if signature == self._signature and self._snapshot is not None and self._etag is not None:
                return self._snapshot, self._etag
            try:
                if stat.st_size > MAX_SNAPSHOT_BYTES:
                    raise ValueError("snapshot exceeds size limit")
                raw = self.path.read_bytes()
                snapshot = json.loads(raw.decode("utf-8"))
                self._validate(snapshot)
            except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
                self.last_error = str(exc)
                if self._snapshot is not None and self._etag is not None:
                    return self._snapshot, self._etag
                raise ApiError(HTTPStatus.SERVICE_UNAVAILABLE, "package snapshot is invalid") from exc
            self._snapshot = snapshot
            self._etag = hashlib.sha256(raw).hexdigest()
            self._signature = signature
            self.last_error = None
            return snapshot, self._etag

    @staticmethod
    def _validate(snapshot: Any) -> None:
        if not isinstance(snapshot, dict) or snapshot.get("schemaVersion") != 1:
            raise ValueError("unsupported snapshot schema")
        packages = snapshot.get("packages")
        if not isinstance(packages, list):
            raise ValueError("snapshot packages must be an array")
        seen = set()
        for package in packages:
            if not isinstance(package, dict):
                raise ValueError("snapshot package must be an object")
            group, name = package.get("group"), package.get("name")
            if not isinstance(group, str) or not GROUP_RE.fullmatch(group):
                raise ValueError("snapshot contains an invalid group")
            if not isinstance(name, str) or not NAME_RE.fullmatch(name):
                raise ValueError("snapshot contains an invalid name")
            coordinate = f"{group}:{name}"
            if package.get("coordinate") != coordinate or coordinate in seen:
                raise ValueError("snapshot contains a duplicate or mismatched coordinate")
            seen.add(coordinate)
            versions = package.get("versions")
            if not isinstance(versions, list) or not versions:
                raise ValueError("snapshot package must contain versions")


def _encode_cursor(offset: int, etag: str) -> str:
    raw = json.dumps({"offset": offset, "etag": etag}, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _decode_cursor(value: str, etag: str) -> int:
    try:
        padded = value + "=" * (-len(value) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8"))
        offset = payload["offset"]
        if payload["etag"] != etag or not isinstance(offset, int) or offset < 0:
            raise ValueError
        return offset
    except (ValueError, KeyError, UnicodeError, json.JSONDecodeError, binascii.Error) as exc:
        raise ApiError(HTTPStatus.BAD_REQUEST, "cursor is invalid or belongs to an older snapshot") from exc


def _summary(package: Dict[str, Any]) -> Dict[str, Any]:
    return {key: value for key, value in package.items() if key != "versions"}


def query_packages(snapshot: Dict[str, Any], etag: str, query: Dict[str, list[str]]) -> Dict[str, Any]:
    def one(name: str) -> Optional[str]:
        values = query.get(name, [])
        if len(values) > 1:
            raise ApiError(HTTPStatus.BAD_REQUEST, f"query parameter {name} may appear only once")
        return values[0] if values else None

    text = (one("q") or "").strip().casefold()
    source = one("source")
    trust = one("trust")
    package_type = one("type")
    minecraft = (one("minecraft") or "").strip().casefold()
    if source is not None and source not in {"nexus", "central"}:
        raise ApiError(HTTPStatus.BAD_REQUEST, "source must be nexus or central")
    if trust is not None and trust not in {"reviewed", "community"}:
        raise ApiError(HTTPStatus.BAD_REQUEST, "trust must be reviewed or community")
    try:
        limit = int(one("limit") or 50)
    except ValueError as exc:
        raise ApiError(HTTPStatus.BAD_REQUEST, "limit must be an integer") from exc
    if not 1 <= limit <= MAX_LIMIT:
        raise ApiError(HTTPStatus.BAD_REQUEST, f"limit must be between 1 and {MAX_LIMIT}")
    cursor = one("cursor")
    offset = _decode_cursor(cursor, etag) if cursor else 0

    matches = []
    for package in snapshot["packages"]:
        if source and source not in package.get("sources", []):
            continue
        if trust and package.get("trust") != trust:
            continue
        if package_type and package_type not in package.get("types", []):
            continue
        searchable = " ".join(
            [
                str(package.get("coordinate", "")),
                str(package.get("name", "")),
                str(package.get("description") or ""),
                str((package.get("display") or {}).get("name", "")),
                str((package.get("display") or {}).get("description", "")),
                " ".join((package.get("display") or {}).get("tags", [])),
                " ".join(
                    author.get("name", "")
                    for author in (package.get("display") or {}).get("authors", [])
                    if isinstance(author, dict)
                ),
                " ".join(package.get("licenses", [])),
                " ".join(package.get("types", [])),
            ]
        ).casefold()
        if text and text not in searchable:
            continue
        if minecraft:
            requirements = [
                requirement.casefold()
                for version in package.get("versions", [])
                for requirement in version.get("minecraftRequirements", [])
                if isinstance(requirement, str)
            ]
            if not any(minecraft in requirement for requirement in requirements):
                continue
        matches.append(_summary(package))
    matches.sort(key=lambda package: package["coordinate"].casefold())
    if offset > len(matches):
        raise ApiError(HTTPStatus.BAD_REQUEST, "cursor offset exceeds the result set")
    end = min(offset + limit, len(matches))
    return {
        "items": matches[offset:end],
        "total": len(matches),
        "limit": limit,
        "nextCursor": _encode_cursor(end, etag) if end < len(matches) else None,
        "snapshotGeneratedAt": snapshot.get("generatedAt"),
    }


def make_handler(store: SnapshotStore):
    class PackageApiHandler(BaseHTTPRequestHandler):
        server_version = "McfpmPackageApi/0.1"

        def do_OPTIONS(self):
            self._send(HTTPStatus.NO_CONTENT, None, cache_control="no-store")

        def do_HEAD(self):
            self._route(send_body=False)

        def do_GET(self):
            self._route(send_body=True)

        def _route(self, *, send_body: bool):
            try:
                snapshot, etag = store.get()
                parsed = urlsplit(self.path)
                path = parsed.path.rstrip("/") or "/"
                if path != "/healthz" and self.headers.get("If-None-Match") == f'"{etag}"':
                    self._send(HTTPStatus.NOT_MODIFIED, None, etag=etag, send_body=False)
                    return
                if path == "/":
                    payload = {
                        "service": "mcfpm-package-index",
                        "schemaVersion": 1,
                        "endpoints": ["/healthz", "/v1/status", "/v1/packages"],
                    }
                elif path == "/healthz":
                    payload = {
                        "ok": True,
                        "generatedAt": snapshot.get("generatedAt"),
                        "packageCount": snapshot.get("packageCount", len(snapshot["packages"])),
                        "servingPreviousSnapshot": store.last_error is not None,
                    }
                elif path == "/v1/status":
                    payload = {
                        "schemaVersion": snapshot["schemaVersion"],
                        "generatedAt": snapshot.get("generatedAt"),
                        "packageCount": snapshot.get("packageCount", len(snapshot["packages"])),
                        "versionCount": snapshot.get("versionCount"),
                        "sourceStatus": snapshot.get("sourceStatus", {}),
                        "rejectedCount": len(snapshot.get("rejected", [])),
                    }
                elif path == "/v1/packages":
                    payload = query_packages(snapshot, etag, parse_qs(parsed.query, keep_blank_values=True))
                elif path.startswith("/v1/packages/"):
                    payload = self._package_detail(snapshot, path)
                else:
                    raise ApiError(HTTPStatus.NOT_FOUND, "route not found")
                self._send(HTTPStatus.OK, payload, etag=etag, send_body=send_body)
            except ApiError as exc:
                self._send(exc.status, {"error": str(exc)}, cache_control="no-store", send_body=send_body)
            except Exception:
                self._send(
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                    {"error": "internal server error"},
                    cache_control="no-store",
                    send_body=send_body,
                )

        @staticmethod
        def _package_detail(snapshot: Dict[str, Any], path: str) -> Dict[str, Any]:
            parts = [unquote(part) for part in path.split("/")[3:]]
            if len(parts) not in {2, 3}:
                raise ApiError(HTTPStatus.NOT_FOUND, "route not found")
            group, name = parts[:2]
            if not GROUP_RE.fullmatch(group) or not NAME_RE.fullmatch(name):
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid package coordinate")
            package = next(
                (item for item in snapshot["packages"] if item["group"] == group and item["name"] == name),
                None,
            )
            if package is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "package not found")
            if len(parts) == 2:
                return package
            version_name = parts[2]
            if not SEMVER_RE.fullmatch(version_name) or "SNAPSHOT" in version_name.upper():
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid package version")
            version = next((item for item in package["versions"] if item["version"] == version_name), None)
            if version is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "package version not found")
            return {"coordinate": package["coordinate"], **version}

        def _send(
            self,
            status: int,
            payload: Optional[Dict[str, Any]],
            *,
            etag: Optional[str] = None,
            cache_control: str = "public, max-age=60, stale-while-revalidate=300",
            send_body: bool = True,
        ):
            body = b"" if payload is None else (json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
            self.send_response(status)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "If-None-Match")
            self.send_header("Cache-Control", cache_control)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            if etag:
                self.send_header("ETag", f'"{etag}"')
            self.end_headers()
            if send_body and body:
                self.wfile.write(body)

        def log_message(self, format, *args):
            super().log_message(format, *args)

    return PackageApiHandler


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Serve the read-only Mcfpm package index API")
    parser.add_argument("--snapshot", required=True, type=Path)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=8770, type=int)
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    store = SnapshotStore(args.snapshot)
    store.get()
    server = ThreadingHTTPServer((args.host, args.port), make_handler(store))
    server.daemon_threads = True
    print(json.dumps({"ok": True, "host": args.host, "port": server.server_port, "snapshot": str(args.snapshot)}))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
