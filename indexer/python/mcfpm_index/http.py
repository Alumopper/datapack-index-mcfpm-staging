from __future__ import annotations

import json
import ssl
from typing import Any, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


USER_AGENT = "mcfpm-package-index/0.1 (+https://package.afox.moe/)"


class FetchError(RuntimeError):
    pass


def fetch_bytes(
    url: str,
    *,
    max_bytes: int = 1024 * 1024,
    timeout: float = 30.0,
) -> Tuple[bytes, str]:
    if urlparse(url).scheme != "https":
        raise FetchError("only HTTPS URLs are allowed")
    request = Request(
        url,
        headers={
            "Accept": "application/json, application/octet-stream;q=0.8",
            "User-Agent": USER_AGENT,
        },
    )
    try:
        with urlopen(request, timeout=timeout, context=ssl.create_default_context()) as response:
            final_url = response.geturl()
            if urlparse(final_url).scheme != "https":
                raise FetchError("HTTPS request redirected to a non-HTTPS URL")
            length = response.headers.get("Content-Length")
            if length is not None and int(length) > max_bytes:
                raise FetchError("response exceeds size limit")
            body = response.read(max_bytes + 1)
    except HTTPError as exc:
        raise FetchError(f"HTTP {exc.code} while fetching repository data") from exc
    except (URLError, TimeoutError, OSError) as exc:
        raise FetchError(f"repository request failed: {exc}") from exc
    if len(body) > max_bytes:
        raise FetchError("response exceeds size limit")
    return body, final_url


def fetch_json(url: str, *, max_bytes: int = 4 * 1024 * 1024) -> Any:
    body, _ = fetch_bytes(url, max_bytes=max_bytes)
    try:
        return json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise FetchError("repository returned invalid UTF-8 JSON") from exc
