import crypto from "node:crypto";

const PACKAGE_ID = /^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?:[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$/;
const SEMVER = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;

function fail(message) {
  throw new Error(message);
}

function exactKeys(value, allowed, field) {
  for (const key of Object.keys(value)) if (!allowed.has(key)) fail(`${field} contains unknown field ${key}`);
}

function text(value, field, maximum, { optional = false, layout = false } = {}) {
  if (optional && value === null) return null;
  if (typeof value !== "string" || !value || value.length > maximum) fail(`${field} is missing or too long`);
  const controls = layout ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/ : /[\u0000-\u001f\u007f]/;
  if (controls.test(value)) fail(`${field} contains control characters`);
  return value;
}

function httpsUrl(value, field, optional = false) {
  const result = text(value, field, 2048, { optional });
  if (result === null) return null;
  let parsed;
  try {
    parsed = new URL(result);
  } catch {
    fail(`${field} must be a public HTTPS URL`);
  }
  if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) {
    fail(`${field} must be a public HTTPS URL`);
  }
  return parsed.toString();
}

function textArray(value, field, maximumItems = 64, maximumLength = 128) {
  if (!Array.isArray(value) || value.length > maximumItems) fail(`${field} must be a bounded array`);
  return value.map((entry) => text(entry, field, maximumLength));
}

function authors(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 20) fail("authors must be a non-empty bounded array");
  return value.map((author) => {
    if (!author || typeof author !== "object" || Array.isArray(author)) fail("author must be an object");
    exactKeys(author, new Set(["name", "avatarUrl", "links"]), "author");
    if (!Array.isArray(author.links) || author.links.length > 20) fail("author links must be a bounded array");
    return {
      name: text(author.name, "author name", 160),
      avatarUrl: httpsUrl(author.avatarUrl, "author avatar URL", true),
      links: author.links.map((link) => {
        if (!link || typeof link !== "object" || Array.isArray(link)) fail("author link must be an object");
        exactKeys(link, new Set(["label", "url"]), "author link");
        return {
          label: text(link.label, "author link label", 64),
          url: httpsUrl(link.url, "author link URL"),
        };
      }),
    };
  });
}

export function normalizeSiteMetadata(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail("site metadata must be an object");
  exactKeys(value, new Set([
    "schema", "packageId", "version", "name", "description", "coverUrl", "authors", "tags",
    "gameVersions", "dependencyNotes", "detailsMarkdown", "projectUrl", "legacyPath",
  ]), "site metadata");
  if (value.schema !== 1) fail("site metadata schema must equal 1");
  if (!PACKAGE_ID.test(value.packageId ?? "")) fail("site metadata packageId is invalid");
  if (!SEMVER.test(value.version ?? "") || /snapshot/i.test(value.version)) fail("site metadata version is invalid");
  const legacyPath = text(value.legacyPath, "legacy path", 512, { optional: true });
  if (legacyPath !== null && (!legacyPath.startsWith("/wheel/resources/") || legacyPath.includes("..") || !legacyPath.endsWith(".html"))) {
    fail("legacy path is invalid");
  }
  return {
    schema: 1,
    packageId: value.packageId,
    version: value.version,
    name: text(value.name, "name", 160),
    description: text(value.description, "description", 2048, { layout: true }),
    coverUrl: httpsUrl(value.coverUrl, "cover URL", true),
    authors: authors(value.authors),
    tags: textArray(value.tags, "tags"),
    gameVersions: textArray(value.gameVersions, "game versions"),
    dependencyNotes: text(value.dependencyNotes, "dependency notes", 8192, { optional: true, layout: true }),
    detailsMarkdown: text(value.detailsMarkdown, "details Markdown", 128 * 1024, { layout: true }),
    projectUrl: httpsUrl(value.projectUrl, "project URL", true),
    legacyPath,
  };
}

function sortValue(value) {
  if (Array.isArray(value)) return value.map(sortValue);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sortValue(value[key])]));
  }
  return value;
}

export function encodeCanonicalSiteMetadata(value) {
  const normalized = normalizeSiteMetadata(value);
  const encoded = `${JSON.stringify(sortValue(normalized), null, 2)}\n`;
  if (Buffer.byteLength(encoded) > 256 * 1024) fail("site metadata exceeds the size limit");
  return encoded;
}

export function siteMetadataSha256(encoded) {
  return crypto.createHash("sha256").update(encoded).digest("hex");
}

export function siteMetadataRepositoryPath(value) {
  const metadata = normalizeSiteMetadata(value);
  const [group, name] = metadata.packageId.split(":");
  return `${group.split(".").map(encodeURIComponent).join("/")}/${encodeURIComponent(name)}/${encodeURIComponent(metadata.version)}/${encodeURIComponent(name)}-${encodeURIComponent(metadata.version)}.mcfpm-site.json`;
}
