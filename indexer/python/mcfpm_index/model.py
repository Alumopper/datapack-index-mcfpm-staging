from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional, Tuple
from urllib.parse import quote


GROUP_RE = re.compile(r"^[A-Za-z0-9_]+(?:[.-][A-Za-z0-9_]+)*$")
NAME_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]*$")
SEMVER_RE = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


class ValidationError(ValueError):
    """Raised when repository data is not safe or does not match its coordinate."""


def validate_coordinate(group: str, name: str, version: str) -> None:
    if not isinstance(group, str) or not GROUP_RE.fullmatch(group):
        raise ValidationError("invalid Maven group")
    if not isinstance(name, str) or not NAME_RE.fullmatch(name):
        raise ValidationError("invalid Maven artifact name")
    if (
        not isinstance(version, str)
        or not SEMVER_RE.fullmatch(version)
        or "SNAPSHOT" in version.upper()
    ):
        raise ValidationError("version must be a SemVer release")


def semver_key(version: str) -> Tuple[object, ...]:
    match = SEMVER_RE.fullmatch(version)
    if not match or "SNAPSHOT" in version.upper():
        raise ValidationError("version must be a SemVer release")
    major, minor, patch = (int(match.group(i)) for i in range(1, 4))
    prerelease = match.group(4)
    if prerelease is None:
        return major, minor, patch, 1, ()
    identifiers = []
    for identifier in prerelease.split("."):
        if identifier.isdigit():
            identifiers.append((0, int(identifier)))
        else:
            identifiers.append((1, identifier))
    return major, minor, patch, 0, tuple(identifiers)


def maven_artifact_path(group: str, name: str, version: str) -> str:
    validate_coordinate(group, name, version)
    segments = [*group.split("."), name, version, f"{name}-{version}.mcfpkg"]
    return "/".join(quote(segment, safe="") for segment in segments)


@dataclass(frozen=True)
class Candidate:
    group: str
    name: str
    version: str
    source: str
    repository_url: str
    descriptor_url: str
    discovered_by: str
    modified_at: Optional[str] = None

    def __post_init__(self) -> None:
        validate_coordinate(self.group, self.name, self.version)
        if self.source not in {"nexus", "central"}:
            raise ValidationError("unsupported repository source")
        if not self.repository_url.startswith("https://"):
            raise ValidationError("repository URL must use HTTPS")
        if not self.descriptor_url.startswith("https://"):
            raise ValidationError("descriptor URL must use HTTPS")

    @property
    def coordinate(self) -> str:
        return f"{self.group}:{self.name}"

    @property
    def gav(self) -> str:
        return f"{self.coordinate}:{self.version}"
