#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

function fail(message) {
  throw new Error(message);
}

function parseArguments(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value || value.startsWith("--") || result[key]) fail("Invalid batch publisher arguments");
    result[key] = value;
    index += 1;
  }
  for (const key of [
    "--manifest", "--audit-report", "--artifact-directory", "--mcfpm", "--repository-url",
    "--repository-name", "--report",
  ]) if (!result[key]) fail(`Missing ${key}`);
  return result;
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function atomicWrite(output, content) {
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const temporary = path.join(path.dirname(output), `.${path.basename(output)}.${process.pid}.tmp`);
  fs.writeFileSync(temporary, content, { encoding: "utf8", mode: 0o600 });
  fs.renameSync(temporary, output);
}

function httpsRepository(value) {
  const parsed = new URL(value);
  if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) fail("Nexus repository URL must be HTTPS");
  return parsed.toString();
}

const args = parseArguments(process.argv.slice(2));
const manifestSha256 = sha256(args["--manifest"]);
const audit = JSON.parse(fs.readFileSync(args["--audit-report"], "utf8"));
if (audit.schema !== 1 || audit.manifestSha256 !== manifestSha256 || !Array.isArray(audit.results)) {
  fail("Batch audit report does not match the trusted migration manifest");
}
if (!process.env.NEXUS_USERNAME || !process.env.NEXUS_PASSWORD) fail("Nexus credentials are missing");
const repositoryUrl = httpsRepository(args["--repository-url"]);
const artifactRoot = path.resolve(args["--artifact-directory"]);
const results = [];

for (const audited of audit.results.filter((result) => result.ok)) {
  const result = { coordinate: audited.coordinate, version: audited.version, ok: false };
  try {
    if (!/^[a-z0-9._@+-]+$/.test(audited.directory ?? "")) fail("Audit report contains an unsafe artifact directory");
    const directory = path.join(artifactRoot, audited.directory);
    if (path.dirname(directory) !== artifactRoot) fail("Artifact directory escaped its root");
    const candidate = path.join(directory, "candidate.mcfpm-import");
    const metadata = path.join(directory, "package.mcfpm-site.json");
    const frozen = JSON.parse(fs.readFileSync(path.join(directory, "frozen.json"), "utf8"));
    if (
      frozen.schema !== 1
      || frozen.coordinate !== audited.coordinate
      || frozen.version !== audited.version
      || frozen.candidateSha256 !== audited.candidateSha256
      || frozen.siteMetadataSha256 !== audited.siteMetadataSha256
      || sha256(candidate) !== frozen.candidateSha256
      || sha256(metadata) !== frozen.siteMetadataSha256
    ) fail("Frozen batch artifact failed hash or identity verification");

    const packageReportPath = path.join(directory, "package-publish.json");
    const packageErrorPath = path.join(directory, "package-publish-error.txt");
    const published = spawnSync(process.execPath, [
      path.join(import.meta.dirname, "publish-mcfpm-import.mjs"),
      "--mcfpm", args["--mcfpm"],
      "--candidate", candidate,
      "--repository-url", repositoryUrl,
      "--repository-name", args["--repository-name"],
      "--report", packageReportPath,
      "--error", packageErrorPath,
    ], {
      cwd: process.cwd(),
      env: process.env,
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
    });
    if (published.error) throw published.error;
    let packageReport;
    try {
      packageReport = JSON.parse(fs.readFileSync(packageReportPath, "utf8"));
    } catch {
      fail(published.stderr?.trim() || "Mcfpm publish did not return JSON");
    }
    if (published.status !== 0 || packageReport.ok !== true) {
      fail(JSON.stringify(packageReport.error || packageReport));
    }

    const siteReportPath = path.join(directory, "site-publish.json");
    const siteErrorPath = path.join(directory, "site-publish-error.txt");
    const sitePublished = spawnSync(process.execPath, [
      path.join(import.meta.dirname, "publish-site-metadata.mjs"),
      "--metadata", metadata,
      "--repository-url", repositoryUrl,
      "--report", siteReportPath,
      "--error", siteErrorPath,
    ], { cwd: process.cwd(), env: process.env, encoding: "utf8", maxBuffer: 4 * 1024 * 1024 });
    if (sitePublished.error) throw sitePublished.error;
    if (sitePublished.status !== 0) fail(sitePublished.stderr?.trim() || "Site metadata publish failed");
    const siteReport = JSON.parse(fs.readFileSync(siteReportPath, "utf8"));
    if (siteReport.siteMetadataSha256 !== frozen.siteMetadataSha256) fail("Published site metadata hash does not match the frozen audit");
    result.ok = true;
    result.packageStatus = packageReport.data?.status || packageReport.status || "published";
    result.siteStatus = siteReport.status;
    result.descriptorSha256 = packageReport.data?.descriptorSha256 || packageReport.descriptorSha256 || null;
  } catch (error) {
    result.error = (error instanceof Error ? error.message : String(error)).slice(0, 2000);
  }
  results.push(result);
  process.stdout.write(`${result.ok ? "PASS" : "FAIL"} ${result.coordinate}@${result.version}${result.error ? `: ${result.error}` : ""}\n`);
}

const report = {
  schema: 1,
  manifestSha256,
  attemptedCount: results.length,
  publishedCount: results.filter((result) => result.ok).length,
  failedCount: results.filter((result) => !result.ok).length,
  results,
};
atomicWrite(args["--report"], `${JSON.stringify(report, null, 2)}\n`);
if (report.failedCount || !report.publishedCount) process.exitCode = 2;
