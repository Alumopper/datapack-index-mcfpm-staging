#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

import { encodeCanonicalSiteMetadata, siteMetadataSha256 } from "./site-metadata-lib.mjs";
import { assertMigrationPublishable } from "./migration-policy.mjs";

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

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function checkedSpawn(commandName, commandArguments, options, failureMessage) {
  const executed = spawnSync(commandName, commandArguments, options);
  if (executed.error) throw executed.error;
  if (executed.status !== 0) fail(executed.stderr?.trim() || executed.stdout?.trim() || failureMessage);
  return executed;
}

function freezeRootPack(entry, bootstrapCandidate, candidate, outputDirectory, mcfpmExecutable, audit) {
  if (
    entry.source?.type !== "github-release-asset"
    || !entry.source.repository
    || !entry.source.ref
    || !entry.source.asset
    || path.basename(entry.source.asset) !== entry.source.asset
    || !entry.subdir
    || !/^[0-9a-f]{64}$/.test(entry.expectedSha256 ?? "")
  ) fail("root-pack migration requires an exact GitHub release asset, audit subdirectory, and SHA-256");

  const downloadDirectory = path.join(path.dirname(candidate), ".root-source");
  fs.mkdirSync(downloadDirectory, { recursive: false });
  checkedSpawn("gh", [
    "release", "download", entry.source.ref,
    "--repo", entry.source.repository,
    "--pattern", entry.source.asset,
    "--dir", downloadDirectory,
  ], { cwd: process.cwd(), env: process.env, encoding: "utf8", maxBuffer: 4 * 1024 * 1024 }, "GitHub asset download failed");
  const rawArchive = path.join(downloadDirectory, entry.source.asset);
  if (!fs.existsSync(rawArchive) || sha256(rawArchive) !== entry.expectedSha256 || audit.data?.rawSha256 !== entry.expectedSha256) {
    fail("root-pack source does not match the pinned or Mcfpm-audited SHA-256");
  }

  const helperDirectory = path.join(outputDirectory, ".root-helper");
  const installation = path.dirname(path.dirname(path.resolve(mcfpmExecutable)));
  const libraries = path.join(installation, "lib", "*");
  const helperSource = path.join(import.meta.dirname, "McfpmRootCandidate.java");
  const javaHome = process.env.JAVA_HOME_17_X64;
  const javac = javaHome ? path.join(javaHome, "bin", process.platform === "win32" ? "javac.exe" : "javac") : "javac";
  const java = javaHome ? path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
  if (!fs.existsSync(path.join(helperDirectory, "McfpmRootCandidate.class"))) {
    fs.mkdirSync(helperDirectory, { recursive: true });
    checkedSpawn(javac, ["-cp", libraries, "-d", helperDirectory, helperSource], {
      cwd: process.cwd(), env: process.env, encoding: "utf8", maxBuffer: 4 * 1024 * 1024,
    }, "Root candidate helper compilation failed");
  }
  const classpath = `${helperDirectory}${path.delimiter}${libraries}`;
  const rewritten = checkedSpawn(java, [
    "-cp", classpath, "McfpmRootCandidate", bootstrapCandidate, rawArchive, entry.subdir, candidate,
  ], { cwd: process.cwd(), env: process.env, encoding: "utf8", maxBuffer: 4 * 1024 * 1024 }, "Root candidate rewrite failed");
  let frozenPayload;
  try {
    frozenPayload = JSON.parse(rewritten.stdout);
  } catch {
    fail("Root candidate helper returned invalid output");
  }
  if (!/^[0-9a-f]{64}$/.test(frozenPayload.normalizedSha256 ?? "") || !Number.isSafeInteger(frozenPayload.normalizedSize)) {
    fail("Root candidate helper returned invalid payload identity");
  }
  audit.data.selectionPath = "/";
  audit.data.normalizedSha256 = frozenPayload.normalizedSha256;
  audit.data.normalizedSize = frozenPayload.normalizedSize;
  fs.rmSync(bootstrapCandidate);
  fs.rmSync(downloadDirectory, { recursive: true });
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
    assertMigrationPublishable(entry);
    if (!PACKAGE_ID.test(entry.coordinate ?? "") || !SEMVER.test(entry.version ?? "") || !entry.license) {
      fail("entry has invalid package identity, version, or license");
    }
    const directory = path.join(outputDirectory, safeName(entry));
    fs.mkdirSync(directory, { recursive: false });
    const candidate = path.join(directory, "candidate.mcfpm-import");
    const cliCandidate = entry.rootPack === true ? `${candidate}.bootstrap` : candidate;
    const executed = spawnSync(args["--mcfpm"], command(entry, cliCandidate, cacheDirectory), {
      cwd: process.cwd(),
      env: process.env,
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
      shell: process.platform === "win32",
    });
    if (executed.error) throw executed.error;
    atomicWrite(path.join(directory, "mcfpm-stderr.txt"), executed.stderr || "");
    let audit;
    try {
      audit = JSON.parse(executed.stdout || "");
    } catch {
      fail(executed.stderr?.trim() || "Mcfpm audit did not return JSON");
    }
    if (executed.status !== 0 || audit.ok !== true || !fs.existsSync(cliCandidate)) {
      const auditError = audit.error?.message || audit.error?.detail || audit.error;
      fail(
        (typeof auditError === "string" ? auditError : JSON.stringify(auditError || audit))
        || executed.stderr?.trim()
        || `Mcfpm audit exited ${executed.status}`,
      );
    }
    if (entry.rootPack === true) freezeRootPack(entry, cliCandidate, candidate, outputDirectory, args["--mcfpm"], audit);
    atomicWrite(path.join(directory, "mcfpm-stdout.txt"), `${JSON.stringify(audit)}\n`);
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
