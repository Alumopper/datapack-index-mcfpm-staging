from __future__ import annotations

import hashlib
import json
from typing import Any, Dict, List
from urllib.parse import urlparse

from .model import Candidate, ValidationError


MAX_SITE_METADATA_BYTES = 256 * 1024


def site_metadata_url(candidate: Candidate) -> str:
    filename = f"{candidate.name}-{candidate.version}.mcfpm-site.json"
    return candidate.descriptor_url.rsplit("/", 1)[0] + "/" + filename


def _exact_keys(value: Dict[str, Any], allowed: set[str], field: str) -> None:
    unknown = set(value) - allowed
    if unknown:
        raise ValidationError(f"site metadata {field} contains unknown field {sorted(unknown)[0]}")


def _string(
    value: Any,
    field: str,
    maximum: int,
    *,
    optional: bool = False,
    layout: bool = False,
) -> str | None:
    if optional and value is None:
        return None
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise ValidationError(f"site metadata {field} is missing or too long")
    allowed_controls = "\r\n\t" if layout else ""
    if any(
        (ord(character) < 0x20 and character not in allowed_controls) or ord(character) == 0x7F
        for character in value
    ):
        raise ValidationError(f"site metadata {field} contains control characters")
    return value


def _https_url(value: Any, field: str, *, optional: bool = False) -> str | None:
    text = _string(value, field, 2048, optional=optional)
    if text is None:
        return None
    parsed = urlparse(text)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValidationError(f"site metadata {field} must be a public HTTPS URL")
    return text


def _string_list(value: Any, field: str, *, maximum_items: int = 64, maximum_length: int = 128) -> List[str]:
    if not isinstance(value, list) or len(value) > maximum_items:
        raise ValidationError(f"site metadata {field} must be a bounded array")
    result = []
    for entry in value:
        result.append(_string(entry, field, maximum_length))
    return result


def _authors(value: Any) -> List[Dict[str, Any]]:
    if not isinstance(value, list) or not 1 <= len(value) <= 20:
        raise ValidationError("site metadata authors must be a non-empty bounded array")
    authors = []
    for author in value:
        if not isinstance(author, dict):
            raise ValidationError("site metadata author must be an object")
        _exact_keys(author, {"name", "avatarUrl", "links"}, "author")
        links_value = author.get("links", [])
        if not isinstance(links_value, list) or len(links_value) > 20:
            raise ValidationError("site metadata author links must be a bounded array")
        links = []
        for link in links_value:
            if not isinstance(link, dict):
                raise ValidationError("site metadata author link must be an object")
            _exact_keys(link, {"label", "url"}, "author link")
            links.append(
                {
                    "label": _string(link.get("label"), "author link label", 64),
                    "url": _https_url(link.get("url"), "author link URL"),
                }
            )
        authors.append(
            {
                "name": _string(author.get("name"), "author name", 160),
                "avatarUrl": _https_url(author.get("avatarUrl"), "author avatar URL", optional=True),
                "links": links,
            }
        )
    return authors


def validate_site_metadata(candidate: Candidate, body: bytes, final_url: str) -> Dict[str, Any]:
    if len(body) > MAX_SITE_METADATA_BYTES:
        raise ValidationError("site metadata exceeds the size limit")
    _https_url(final_url, "final URL")
    try:
        value = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValidationError("site metadata is not valid UTF-8 JSON") from exc
    if not isinstance(value, dict) or value.get("schema") != 1:
        raise ValidationError("site metadata schema must equal 1")
    _exact_keys(
        value,
        {
            "schema",
            "packageId",
            "version",
            "name",
            "description",
            "coverUrl",
            "authors",
            "tags",
            "gameVersions",
            "dependencyNotes",
            "detailsMarkdown",
            "projectUrl",
            "legacyPath",
        },
        "document",
    )
    if value.get("packageId") != candidate.coordinate or value.get("version") != candidate.version:
        raise ValidationError("site metadata does not match the repository coordinate")
    legacy_path = _string(value.get("legacyPath"), "legacy path", 512, optional=True)
    if legacy_path is not None and (
        not legacy_path.startswith("/wheel/resources/")
        or ".." in legacy_path
        or not legacy_path.endswith(".html")
    ):
        raise ValidationError("site metadata legacy path is invalid")
    normalized = {
        "schema": 1,
        "packageId": candidate.coordinate,
        "version": candidate.version,
        "name": _string(value.get("name"), "name", 160),
        "description": _string(value.get("description"), "description", 2048, layout=True),
        "coverUrl": _https_url(value.get("coverUrl"), "cover URL", optional=True),
        "authors": _authors(value.get("authors")),
        "tags": _string_list(value.get("tags", []), "tags"),
        "gameVersions": _string_list(value.get("gameVersions", []), "game versions"),
        "dependencyNotes": _string(
            value.get("dependencyNotes"), "dependency notes", 8192, optional=True, layout=True
        ),
        "detailsMarkdown": _string(value.get("detailsMarkdown"), "details Markdown", 128 * 1024, layout=True),
        "projectUrl": _https_url(value.get("projectUrl"), "project URL", optional=True),
        "legacyPath": legacy_path,
        "metadataUrl": final_url,
        "metadataSha256": hashlib.sha256(body).hexdigest(),
        "metadataSize": len(body),
    }
    return normalized


def site_summary(site: Dict[str, Any] | None) -> Dict[str, Any] | None:
    if site is None:
        return None
    return {
        key: site[key]
        for key in (
            "name",
            "description",
            "coverUrl",
            "authors",
            "tags",
            "gameVersions",
            "projectUrl",
            "legacyPath",
        )
    }
