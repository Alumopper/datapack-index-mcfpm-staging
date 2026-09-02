from __future__ import annotations

import hashlib
import json
from typing import Any, Callable, Dict, List, Tuple
from urllib.parse import urlparse

from .http import NotFoundError, fetch_bytes
from .model import Candidate, ValidationError
from .site_metadata import site_metadata_url, validate_site_metadata


BytesFetcher = Callable[[str], Tuple[bytes, str]]
MAX_DESCRIPTOR_BYTES = 1024 * 1024


def _bounded_string(value: Any, field: str, maximum: int = 512) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise ValidationError(f"descriptor {field} is missing or too long")
    if any(ord(character) < 0x20 for character in value):
        raise ValidationError(f"descriptor {field} contains control characters")
    return value


def _safe_https_url(value: Any) -> str | None:
    if not isinstance(value, str) or len(value) > 2048:
        return None
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        return None
    return value


def _normalise_dependencies(value: Any) -> List[str]:
    if value is None:
        return []
    if not isinstance(value, list) or len(value) > 100:
        raise ValidationError("descriptor dependencies must be a bounded array")
    result = []
    for dependency in value:
        if isinstance(dependency, str):
            text = _bounded_string(dependency, "dependency", 512)
        elif isinstance(dependency, dict):
            safe = {}
            for key in ("packageId", "version", "optional"):
                if key in dependency and isinstance(dependency[key], (str, bool)):
                    safe[key] = dependency[key]
            if not safe:
                raise ValidationError("descriptor dependency object has no supported fields")
            text = json.dumps(safe, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        else:
            raise ValidationError("descriptor dependency has an unsupported type")
        if len(text) > 512:
            raise ValidationError("descriptor dependency is too long")
        result.append(text)
    return result


def validate_descriptor(candidate: Candidate, body: bytes, final_url: str) -> Dict[str, Any]:
    if len(body) > MAX_DESCRIPTOR_BYTES:
        raise ValidationError("descriptor exceeds the size limit")
    if _safe_https_url(final_url) is None:
        raise ValidationError("descriptor final URL is not public HTTPS")
    try:
        descriptor = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValidationError("descriptor is not valid UTF-8 JSON") from exc
    if not isinstance(descriptor, dict) or descriptor.get("schema") != 1:
        raise ValidationError("descriptor schema must equal 1")
    if descriptor.get("packageId") != candidate.coordinate:
        raise ValidationError("descriptor packageId does not match the repository coordinate")
    if descriptor.get("version") != candidate.version:
        raise ValidationError("descriptor version does not match the repository coordinate")
    license_id = _bounded_string(descriptor.get("license"), "license", 128)
    artifacts = descriptor.get("artifacts")
    if not isinstance(artifacts, list) or not 1 <= len(artifacts) <= 32:
        raise ValidationError("descriptor artifacts must be a non-empty bounded array")
    types = set()
    classifiers = set()
    requirements = []
    minecraft = descriptor.get("minecraft")
    if minecraft is not None:
        requirements.append(_bounded_string(minecraft, "minecraft requirement", 256))
    upstream_urls = []
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise ValidationError("descriptor artifact must be an object")
        types.add(_bounded_string(artifact.get("type"), "artifact type", 128))
        classifier = artifact.get("classifier")
        if classifier is not None:
            classifiers.add(_bounded_string(classifier, "artifact classifier", 128))
        requires = artifact.get("requires", [])
        if not isinstance(requires, list) or len(requires) > 64:
            raise ValidationError("artifact requirements must be a bounded array")
        for requirement in requires:
            if isinstance(requirement, str):
                requirements.append(_bounded_string(requirement, "artifact requirement", 256))
            elif isinstance(requirement, dict):
                text = json.dumps(requirement, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
                if len(text) > 512:
                    raise ValidationError("artifact requirement is too long")
                requirements.append(text)
            else:
                raise ValidationError("artifact requirement has an unsupported type")
        source = artifact.get("source")
        if isinstance(source, dict):
            source_url = _safe_https_url(source.get("uri"))
            if source_url:
                upstream_urls.append(source_url)
    description = descriptor.get("description")
    if description is not None:
        description = _bounded_string(description, "description", 2048)
    return {
        "version": candidate.version,
        "source": candidate.source,
        "trust": "reviewed" if candidate.source == "nexus" else "community",
        "repositoryUrl": candidate.repository_url,
        "descriptorUrl": candidate.descriptor_url,
        "descriptorFinalUrl": final_url,
        "descriptorSha256": hashlib.sha256(body).hexdigest(),
        "descriptorSize": len(body),
        "license": license_id,
        "types": sorted(types),
        "classifiers": sorted(classifiers),
        "minecraftRequirements": sorted(set(requirements)),
        "dependencies": _normalise_dependencies(descriptor.get("dependencies")),
        "upstreamUrls": sorted(set(upstream_urls)),
        "description": description,
        "discoveredBy": candidate.discovered_by,
    }


def fetch_and_validate_descriptor(
    candidate: Candidate,
    *,
    get_bytes: BytesFetcher = fetch_bytes,
) -> Dict[str, Any]:
    body, final_url = get_bytes(candidate.descriptor_url)
    descriptor = validate_descriptor(candidate, body, final_url)
    descriptor["site"] = None
    if candidate.source == "nexus":
        try:
            site_body, site_final_url = get_bytes(site_metadata_url(candidate))
        except NotFoundError:
            pass
        else:
            descriptor["site"] = validate_site_metadata(candidate, site_body, site_final_url)
    return descriptor
