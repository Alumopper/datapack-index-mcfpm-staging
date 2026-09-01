from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional
from urllib.parse import urlencode

from .http import fetch_json
from .model import Candidate, ValidationError, maven_artifact_path, validate_coordinate


JsonFetcher = Callable[[str], Any]


def _join_repository_url(repository_url: str, artifact_path: str) -> str:
    return repository_url.rstrip("/") + "/" + artifact_path.lstrip("/")


def collect_nexus(
    search_url: str,
    repository_url: str,
    *,
    get_json: JsonFetcher = fetch_json,
    max_pages: int = 1000,
) -> List[Candidate]:
    candidates: List[Candidate] = []
    continuation: Optional[str] = None
    seen_tokens = set()
    for _ in range(max_pages):
        url = search_url
        if continuation:
            separator = "&" if "?" in url else "?"
            url += separator + urlencode({"continuationToken": continuation})
        payload = get_json(url)
        if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
            raise ValidationError("Nexus search response has an invalid shape")
        for item in payload["items"]:
            if not isinstance(item, dict) or not isinstance(item.get("maven2"), dict):
                raise ValidationError("Nexus asset is missing Maven coordinates")
            maven = item["maven2"]
            group = maven.get("groupId")
            name = maven.get("artifactId")
            version = maven.get("version")
            validate_coordinate(group, name, version)
            if maven.get("extension") != "mcfpkg":
                raise ValidationError("Nexus returned a non-mcfpkg asset")
            expected_path = maven_artifact_path(group, name, version)
            actual_path = str(item.get("path", "")).lstrip("/")
            if actual_path != expected_path:
                raise ValidationError("Nexus asset path does not match its Maven coordinate")
            candidates.append(
                Candidate(
                    group=group,
                    name=name,
                    version=version,
                    source="nexus",
                    repository_url=repository_url,
                    descriptor_url=_join_repository_url(repository_url, expected_path),
                    discovered_by="nexus-search",
                )
            )
        continuation = payload.get("continuationToken")
        if continuation is None:
            return candidates
        if not isinstance(continuation, str) or not continuation or continuation in seen_tokens:
            raise ValidationError("Nexus pagination token is invalid or repeated")
        seen_tokens.add(continuation)
    raise ValidationError("Nexus pagination exceeded the configured limit")


def collect_central_search(
    search_url: str,
    repository_url: str,
    *,
    get_json: JsonFetcher = fetch_json,
    rows: int = 200,
    max_pages: int = 1000,
) -> List[Candidate]:
    if not 1 <= rows <= 200:
        raise ValueError("Central Search rows must be between 1 and 200")
    candidates: List[Candidate] = []
    start = 0
    for _ in range(max_pages):
        query = urlencode({"q": "p:mcfpkg", "core": "gav", "rows": rows, "start": start, "wt": "json"})
        separator = "&" if "?" in search_url else "?"
        payload = get_json(search_url + separator + query)
        response = payload.get("response") if isinstance(payload, dict) else None
        docs = response.get("docs") if isinstance(response, dict) else None
        total = response.get("numFound") if isinstance(response, dict) else None
        if not isinstance(docs, list) or not isinstance(total, int) or total < 0:
            raise ValidationError("Central Search response has an invalid shape")
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("p") != "mcfpkg":
                raise ValidationError("Central Search returned a non-mcfpkg document")
            group, name, version = doc.get("g"), doc.get("a"), doc.get("v")
            validate_coordinate(group, name, version)
            path = maven_artifact_path(group, name, version)
            candidates.append(
                Candidate(
                    group=group,
                    name=name,
                    version=version,
                    source="central",
                    repository_url=repository_url,
                    descriptor_url=_join_repository_url(repository_url, path),
                    discovered_by="central-search",
                    modified_at=str(doc["timestamp"]) if "timestamp" in doc else None,
                )
            )
        start += len(docs)
        if start >= total:
            return candidates
        if not docs:
            raise ValidationError("Central Search pagination stopped before numFound")
    raise ValidationError("Central Search pagination exceeded the configured limit")


def collect_central_full(path: Optional[Path], repository_url: str) -> List[Candidate]:
    if path is None or not path.exists():
        return []
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValidationError("Central full-index candidate file is invalid") from exc
    entries = payload.get("candidates") if isinstance(payload, dict) else None
    if not isinstance(entries, list):
        raise ValidationError("Central full-index candidate file has an invalid shape")
    candidates = []
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValidationError("Central full-index candidate is not an object")
        group, name, version = entry.get("group"), entry.get("name"), entry.get("version")
        validate_coordinate(group, name, version)
        artifact_path = maven_artifact_path(group, name, version)
        candidates.append(
            Candidate(
                group=group,
                name=name,
                version=version,
                source="central",
                repository_url=repository_url,
                descriptor_url=_join_repository_url(repository_url, artifact_path),
                discovered_by="central-full-index",
            )
        )
    return candidates


def deduplicate_candidates(candidates: Iterable[Candidate]) -> List[Candidate]:
    source_priority = {"nexus": 0, "central": 1}
    discovery_priority = {"central-search": 0, "central-full-index": 1, "nexus-search": 0}
    selected: Dict[str, Candidate] = {}
    for candidate in candidates:
        previous = selected.get(candidate.gav)
        if previous is None:
            selected[candidate.gav] = candidate
            continue
        candidate_key = (source_priority[candidate.source], discovery_priority.get(candidate.discovered_by, 99))
        previous_key = (source_priority[previous.source], discovery_priority.get(previous.discovered_by, 99))
        if candidate_key < previous_key:
            selected[candidate.gav] = candidate
    return sorted(selected.values(), key=lambda value: (value.group, value.name, value.version, value.source))
