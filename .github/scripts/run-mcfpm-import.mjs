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
    if (!key?.startsWith("--") || !value || value.startsWith("--") || result[key]) fail("Invalid runner arguments");
    result[key] = value;
    index += 1;
  }
  for (const key of ["--config", "--mcfpm", "--candidate", "--report", "--error"]) {
    if (!result[key]) fail(`Missing ${key}`);
  }
  return result;
}

function addOption(argumentsList, name, value) {
  if (value !== null && value !== undefined && value !== "") argumentsList.push(name, value);
}

function buildCommand(config, candidatePath) {
  const argumentsList = ["--json", "import"];
  if (config.sourceType === "github-release-asset" || config.sourceType === "github-archive") {
    argumentsList.push("github", config.repository, "--tag", config.ref);
    addOption(argumentsList, "--source", config.sourceType === "github-release-asset" ? "release-asset" : "archive");
    addOption(argumentsList, "--asset", config.asset);
    addOption(argumentsList, "--expected-sha256", config.expectedSha256);
  } else if (config.sourceType === "url") {
    argumentsList.push("url", config.url);
    addOption(argumentsList, "--expected-sha256", config.expectedSha256);
  } else {
    fail("Unsupported source type in parsed issue");
  }
  addOption(argumentsList, "--package", config.package);
  addOption(argumentsList, "--version", config.version);
  addOption(argumentsList, "--license", config.license);
  addOption(argumentsList, "--type", config.contentType);
  addOption(argumentsList, "--subdir", config.subdir);
  addOption(argumentsList, "--nested-zip", config.nestedZip);
  addOption(argumentsList, "--minecraft", config.minecraft);
  for (const dependency of config.dependencies ?? []) addOption(argumentsList, "--dependency", dependency);
  argumentsList.push("--audit-only", "--candidate-output", candidatePath);
  return argumentsList;
}

function atomicWrite(output, content) {
  const directory = path.dirname(output);
  fs.mkdirSync(directory, { recursive: true });
  const temporary = path.join(directory, `.${path.basename(output)}.${process.pid}.tmp`);
  fs.writeFileSync(temporary, content, { encoding: "utf8", mode: 0o600 });
  fs.renameSync(temporary, output);
}

try {
  const argumentsObject = parseArguments(process.argv.slice(2));
  const config = JSON.parse(fs.readFileSync(argumentsObject["--config"], "utf8"));
  const command = buildCommand(config, argumentsObject["--candidate"]);
  const result = spawnSync(argumentsObject["--mcfpm"], command, {
    cwd: process.cwd(),
    env: process.env,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  const stdout = result.stdout ?? "";
  const stderr = result.stderr ?? "";
  let report;
  try {
    report = JSON.parse(stdout);
  } catch {
    report = { schema: 1, ok: false, error: stderr.trim() || "Mcfpm audit did not emit JSON" };
  }
  if (fs.existsSync(argumentsObject["--candidate"])) {
    report.data = {
      ...(report.data ?? {}),
      candidateSha256: crypto.createHash("sha256").update(fs.readFileSync(argumentsObject["--candidate"])).digest("hex"),
    };
  }
  atomicWrite(argumentsObject["--report"], `${JSON.stringify(report, null, 2)}\n`);
  atomicWrite(argumentsObject["--error"], stderr);
  process.stdout.write(stdout);
  process.stderr.write(stderr);
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
} catch (error) {
  const message = `${error instanceof Error ? error.message : String(error)}\n`;
  try {
    const argumentsObject = parseArguments(process.argv.slice(2));
    atomicWrite(argumentsObject["--error"], message);
  } catch {
    // Preserve the original failure when the runner arguments themselves are malformed.
  }
  process.stderr.write(message);
  process.exitCode = 2;
}
