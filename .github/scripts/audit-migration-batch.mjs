#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

import { encodeCanonicalSiteMetadata, siteMetadataSha256 } from "./site-metadata-lib.mjs";

const PACKAGE_ID = /^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?:[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$/;
const SEMVER = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;

function fail(message) {
  throw new Error(message);
}

function parseArguments(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value || value.startsWith("--") || result[key]) fail("Invalid batch audit arguments");
    result[key] = value;
    index += 1;
  }
  for (const key of ["--manifest", "--mcfpm", "--output-directory", "--report"]) if (!result[key]) fail(`Missing ${key}`);
  return result;
}

function safeName(entry) {
  return `${entry.coordinate}@${entry.version}`.replace(/[^a-z0-9._@+-]/g, "_");
}

function addOption(args, name, value) {
  if (value !== null && value !== undefined && value !== "") args.push(name, value);
}

function command(entry, candidate, cacheDirectory) {
  const args = ["--json", "import"];
  if (entry.source?.type === "github-release-asset" || entry.source?.type === "github-archive") {
    args.push("github", entry.source.repository, "--tag", entry.source.ref);
    args.push("--source", entry.source.type === "github-release-asset" ? "release-asset" : "archive");
    addOption(args, "--asset", entry.source.asset);
    addOption(args, "--expected-sha256", entry.expectedSha256);
    args.push("--github-token-env", "GH_TOKEN");
  } else if (entry.source?.type === "url") {
    args.push("url", entry.source.url);
    addOption(args, "--expected-sha256", entry.expectedSha256);
  } else {
    fail(`${entry.coordinate} has an unsupported source type`);
  }
  args.push("--package", entry.coordinate, "--version", entry.version, "--license", entry.license, "--cache-dir", cacheDirectory);
  addOption(args, "--subdir", entry.subdir);
  addOption(args, "--nested-zip", entry.nestedZip);
  addOption(args, "--minecraft", entry.minecraft);
  for (const dependency of entry.dependencies ?? []) args.push("--dependency", dependency);
  args.push("--audit-only", "--candidate-output", candidate);
  return args;
}

function siteMetadata(entry) {
  const site = entry.site;
  if (!site || typeof site !== "object") fail(`${entry.coordinate} has no site metadata`);
  return encodeCanonicalSiteMetadata({
    schema: 1,
    packageId: entry.coordinate,
    version: entry.version,
    name: site.name,
    description: site.description,
    coverUrl: site.cover || null,
    authors: site.authors,
    tags: site.tags,
    gameVersions: site.gameVersions,
    dependencyNotes: site.dependencyNotes || null,
    detailsMarkdown: site.details,
    projectUrl: site.projectUrl || null,
    legacyPath: site.legacyPath || null,
  });
}

function atomicWrite(output, content) {
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const temporary = path.join(path.dirname(output), `.${path.basename(output)}.${process.pid}.tmp`);
  fs.writeFileSync(temporary, content, { encoding: "utf8", mode: 0o600 });
  fs.renameSync(temporary, output);
}

const args = parseArguments(process.argv.slice(2));
const manifestRaw = fs.readFileSync(args["--manifest"]);
const manifest = JSON.parse(manifestRaw.toString("utf8"));
if (manifest.schema !== 1 || !Array.isArray(manifest.entries) || manifest.entries.length > 200) fail("Migration manifest is invalid");
const outputDirectory = path.resolve(args["--output-directory"]);
if (fs.existsSync(outputDirectory) && fs.readdirSync(outputDirectory).length) fail("Batch audit output directory must be empty");
fs.mkdirSync(outputDirectory, { recursive: true });
const cacheDirectory = path.join(outputDirectory, ".cache");
fs.mkdirSync(cacheDirectory, { recursive: true });

const results = [];
for (const entry of manifest.entries) {
  if (entry.status !== "ready-for-cli-audit") continue;
  const result = { coordinate: entry.coordinate, version: entry.version, ok: false };
  try {
    if (!PACKAGE_ID.test(entry.coordinate ?? "") || !SEMVER.test(entry.version ?? "") || !entry.license) {
      fail("entry has invalid package identity, version, or license");
    }
    const directory = path.join(outputDirectory, safeName(entry));
    fs.mkdirSync(directory, { recursive: false });
    const candidate = path.join(directory, "candidate.mcfpm-import");
    const executed = spawnSync(args["--mcfpm"], command(entry, candidate, cacheDirectory), {
      cwd: process.cwd(),
      env: process.env,
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
      shell: process.platform === "win32",
    });
    if (executed.error) throw executed.error;
    atomicWrite(path.join(directory, "mcfpm-stdout.txt"), executed.stdout || "");
    atomicWrite(path.join(directory, "mcfpm-stderr.txt"), executed.stderr || "");
    let audit;
    try {
      audit = JSON.parse(executed.stdout || "");
    } catch {
      fail(executed.stderr?.trim() || "Mcfpm audit did not return JSON");
    }
    if (executed.status !== 0 || audit.ok !== true || !fs.existsSync(candidate)) {
      const auditError = audit.error?.message || audit.error?.detail || audit.error;
      fail(
        (typeof auditError === "string" ? auditError : JSON.stringify(auditError || audit))
        || executed.stderr?.trim()
        || `Mcfpm audit exited ${executed.status}`,
      );
    }
    const metadata = siteMetadata(entry);
    const metadataPath = path.join(directory, "package.mcfpm-site.json");
    atomicWrite(metadataPath, metadata);
    const candidateSha256 = crypto.createHash("sha256").update(fs.readFileSync(candidate)).digest("hex");
    const frozen = {
      schema: 1,
      coordinate: entry.coordinate,
      version: entry.version,
      candidateSha256,
      siteMetadataSha256: siteMetadataSha256(metadata),
      audit,
    };
    atomicWrite(path.join(directory, "frozen.json"), `${JSON.stringify(frozen, null, 2)}\n`);
    atomicWrite(path.join(directory, "entry.json"), `${JSON.stringify(entry, null, 2)}\n`);
    result.ok = true;
    result.directory = path.basename(directory);
    result.candidateSha256 = candidateSha256;
    result.siteMetadataSha256 = frozen.siteMetadataSha256;
  } catch (error) {
    result.error = (error instanceof Error ? error.message : String(error)).slice(0, 2000);
  }
  results.push(result);
  process.stdout.write(`${result.ok ? "PASS" : "FAIL"} ${entry.coordinate}@${entry.version}${result.error ? `: ${result.error}` : ""}\n`);
}

const report = {
  schema: 1,
  manifestSha256: crypto.createHash("sha256").update(manifestRaw).digest("hex"),
  auditedCount: results.length,
  passedCount: results.filter((result) => result.ok).length,
  failedCount: results.filter((result) => !result.ok).length,
  results,
};
atomicWrite(args["--report"], `${JSON.stringify(report, null, 2)}\n`);
process.stdout.write(`${JSON.stringify({ auditedCount: report.auditedCount, passedCount: report.passedCount, failedCount: report.failedCount })}\n`);
if (!report.passedCount) process.exitCode = 2;
