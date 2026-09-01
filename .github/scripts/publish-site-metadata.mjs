#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

import {
  encodeCanonicalSiteMetadata,
  normalizeSiteMetadata,
  siteMetadataRepositoryPath,
  siteMetadataSha256,
} from "./site-metadata-lib.mjs";

function fail(message) {
  throw new Error(message);
}

function parseArguments(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value || value.startsWith("--") || result[key]) fail("Invalid site publisher arguments");
    result[key] = value;
    index += 1;
  }
  for (const key of ["--metadata", "--repository-url", "--report", "--error"]) if (!result[key]) fail(`Missing ${key}`);
  return result;
}

function atomicWrite(output, content) {
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const temporary = path.join(path.dirname(output), `.${path.basename(output)}.${process.pid}.tmp`);
  fs.writeFileSync(temporary, content, { encoding: "utf8", mode: 0o600 });
  fs.renameSync(temporary, output);
}

function repositoryBase(value) {
  const parsed = new URL(value);
  if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) fail("Nexus URL must be HTTPS");
  parsed.search = "";
  parsed.hash = "";
  return parsed.toString().replace(/\/?$/, "/");
}

async function responseBytes(response, maximum = 256 * 1024) {
  const length = Number(response.headers.get("content-length"));
  if (Number.isFinite(length) && length > maximum) fail("Existing site metadata exceeds the size limit");
  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.length > maximum) fail("Existing site metadata exceeds the size limit");
  return bytes;
}

let args;
try {
  args = parseArguments(process.argv.slice(2));
  const raw = fs.readFileSync(args["--metadata"], "utf8");
  const metadata = normalizeSiteMetadata(JSON.parse(raw));
  const canonical = encodeCanonicalSiteMetadata(metadata);
  if (raw !== canonical) fail("Site metadata is not canonical or changed after audit");
  const url = repositoryBase(args["--repository-url"]) + siteMetadataRepositoryPath(metadata);
  const existing = await fetch(url, {
    headers: { Accept: "application/json" },
    redirect: "follow",
    signal: AbortSignal.timeout(30_000),
  });
  if (existing.url && new URL(existing.url).protocol !== "https:") fail("Nexus redirected to a non-HTTPS URL");
  let status;
  if (existing.status === 200) {
    const existingRaw = (await responseBytes(existing)).toString("utf8");
    let existingCanonical;
    try {
      existingCanonical = encodeCanonicalSiteMetadata(JSON.parse(existingRaw));
    } catch {
      fail("Coordinate already contains invalid or different site metadata");
    }
    if (existingCanonical !== canonical) fail("Coordinate already contains different site metadata");
    status = "already_present";
  } else if (existing.status === 404) {
    const username = process.env.NEXUS_USERNAME;
    const password = process.env.NEXUS_PASSWORD;
    if (!username || !password) fail("Nexus credentials are missing");
    const uploaded = await fetch(url, {
      method: "PUT",
      headers: {
        Authorization: `Basic ${Buffer.from(`${username}:${password}`, "utf8").toString("base64")}`,
        "Content-Type": "application/json; charset=utf-8",
        "Content-Length": String(Buffer.byteLength(canonical)),
      },
      body: canonical,
      redirect: "manual",
      signal: AbortSignal.timeout(30_000),
    });
    if (![200, 201, 204].includes(uploaded.status)) fail(`Nexus rejected site metadata with HTTP ${uploaded.status}`);
    status = "published";
  } else {
    fail(`Nexus site metadata probe returned HTTP ${existing.status}`);
  }
  const report = {
    schema: 1,
    ok: true,
    status,
    packageId: metadata.packageId,
    version: metadata.version,
    siteMetadataSha256: siteMetadataSha256(canonical),
    url,
  };
  atomicWrite(args["--report"], `${JSON.stringify(report, null, 2)}\n`);
  atomicWrite(args["--error"], "");
  process.stdout.write(`${JSON.stringify(report)}\n`);
} catch (error) {
  const message = `${error instanceof Error ? error.message : String(error)}\n`;
  if (args) {
    try {
      atomicWrite(args["--error"], message);
    } catch {
      // Preserve the original error.
    }
  }
  process.stderr.write(message);
  process.exitCode = 2;
}
