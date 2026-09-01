import crypto from "node:crypto";

const MAXIMUM_DESCRIPTOR_SIZE = 256 * 1024;
const MAVEN_COMPONENT = /^[A-Za-z0-9][A-Za-z0-9_.-]*$/;
const RELEASE_VERSION = /^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;

function fail(message) {
  throw new Error(message);
}

function repositoryBase(value) {
  const parsed = new URL(value);
  if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) {
    fail("Nexus repository URL must be HTTPS");
  }
  parsed.search = "";
  parsed.hash = "";
  return parsed.toString().replace(/\/?$/, "/");
}

function candidateIdentity(candidate) {
  if (!candidate || candidate.schema !== 1 || typeof candidate.packageId !== "string") {
    fail("Candidate metadata is invalid");
  }
  const coordinate = candidate.packageId.split(":");
  if (coordinate.length !== 2 || coordinate.some((part) => !MAVEN_COMPONENT.test(part))) {
    fail("Candidate contains an invalid Maven coordinate");
  }
  if (typeof candidate.version !== "string" || !RELEASE_VERSION.test(candidate.version)) {
    fail("Candidate contains an invalid release version");
  }
  if (!candidate.payload || !candidate.source) fail("Candidate metadata is incomplete");
  return { group: coordinate[0], name: coordinate[1] };
}

function comparable(value) {
  if (Array.isArray(value)) return value.map(comparable);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, comparable(value[key])]));
  }
  return value;
}

function stableCandidateDescriptor(candidate) {
  candidateIdentity(candidate);
  const source = candidate.source;
  const payload = candidate.payload;
  return comparable({
    schema: candidate.schema,
    packageId: candidate.packageId,
    version: candidate.version,
    license: candidate.license,
    minecraft: candidate.minecraft,
    dependencies: candidate.dependencies,
    artifact: {
      type: payload.type,
      classifier: payload.classifier,
      sha256: payload.normalizedSha256,
      size: payload.normalizedSize,
      extension: "zip",
      executable: false,
      source: {
        kind: source.kind,
        immutableVersion: source.immutableVersion ?? null,
        sha256: source.rawSha256,
        size: source.rawSize,
        revision: source.revision ?? null,
        path: source.selectionPath ?? null,
        upstreamId: source.upstreamId == null ? null : String(source.upstreamId),
        redistributionLicense: candidate.license,
      },
    },
  });
}

function stablePublishedDescriptor(descriptor) {
  if (!descriptor || !Array.isArray(descriptor.artifacts) || descriptor.artifacts.length !== 1) return null;
  const artifact = descriptor.artifacts[0];
  if (!artifact || !artifact.source) return null;
  const source = artifact.source;
  return comparable({
    schema: descriptor.schema,
    packageId: descriptor.packageId,
    version: descriptor.version,
    license: descriptor.license,
    minecraft: descriptor.minecraft,
    dependencies: descriptor.dependencies,
    artifact: {
      type: artifact.type,
      classifier: artifact.classifier,
      sha256: artifact.sha256,
      size: artifact.size,
      extension: artifact.extension,
      executable: artifact.executable,
      source: {
        kind: source.kind,
        immutableVersion: source.immutableVersion ?? null,
        sha256: source.sha256,
        size: source.size,
        revision: source.revision ?? null,
        path: source.path ?? null,
        upstreamId: source.upstreamId == null ? null : String(source.upstreamId),
        redistributionLicense: source.redistributionLicense,
      },
    },
  });
}

export function candidateDescriptorUrl(candidate, repositoryUrl) {
  const { group, name } = candidateIdentity(candidate);
  const groupPath = group.split(".").map(encodeURIComponent).join("/");
  const encodedName = encodeURIComponent(name);
  const encodedVersion = encodeURIComponent(candidate.version);
  return `${repositoryBase(repositoryUrl)}${groupPath}/${encodedName}/${encodedVersion}/${encodedName}-${encodedVersion}.mcfpkg`;
}

export function descriptorMatchesCandidate(candidate, descriptor) {
  const published = stablePublishedDescriptor(descriptor);
  return published !== null
    && JSON.stringify(stableCandidateDescriptor(candidate)) === JSON.stringify(published);
}

async function responseBytes(response) {
  const length = Number(response.headers.get("content-length"));
  if (Number.isFinite(length) && length > MAXIMUM_DESCRIPTOR_SIZE) {
    fail("Existing Mcfpm descriptor exceeds the size limit");
  }
  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.length > MAXIMUM_DESCRIPTOR_SIZE) fail("Existing Mcfpm descriptor exceeds the size limit");
  return bytes;
}

export async function probeExistingCandidate(candidate, repositoryUrl, fetchImplementation = fetch) {
  const url = candidateDescriptorUrl(candidate, repositoryUrl);
  const response = await fetchImplementation(url, {
    headers: { Accept: "application/json" },
    redirect: "follow",
    signal: AbortSignal.timeout(30_000),
  });
  if (response.url && new URL(response.url).protocol !== "https:") {
    fail("Nexus redirected to a non-HTTPS URL");
  }
  if (response.status === 404) return { status: "missing", url };
  if (response.status !== 200) fail(`Nexus descriptor probe returned HTTP ${response.status}`);

  const bytes = await responseBytes(response);
  let descriptor;
  try {
    descriptor = JSON.parse(bytes.toString("utf8"));
  } catch {
    fail("Release coordinate already contains an invalid Mcfpm descriptor");
  }
  if (!descriptorMatchesCandidate(candidate, descriptor)) {
    fail("Release coordinate already contains different stable content and cannot be replaced");
  }
  return {
    status: "already_present",
    url,
    descriptorSha256: crypto.createHash("sha256").update(bytes).digest("hex"),
  };
}
