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
    if (!key?.startsWith("--") || !value || value.startsWith("--") || result[key]) fail("Invalid publisher arguments");
    result[key] = value;
    index += 1;
  }
  for (const key of ["--mcfpm", "--candidate", "--repository-url", "--repository-name", "--report", "--error"]) {
    if (!result[key]) fail(`Missing ${key}`);
  }
  return result;
}

function requireHttps(value) {
  const parsed = new URL(value);
  if (parsed.protocol !== "https:" || parsed.username || parsed.password || !parsed.hostname) fail("Nexus URL must be HTTPS");
  return parsed.toString();
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
  const candidatePath = argumentsObject["--candidate"];
  const candidateSha256 = crypto.createHash("sha256").update(fs.readFileSync(candidatePath)).digest("hex");
  const command = [
    "--json",
    "--yes",
    "import",
    "publish",
    "--candidate",
    candidatePath,
    "--repository-url",
    requireHttps(argumentsObject["--repository-url"]),
    "--repository-name",
    argumentsObject["--repository-name"],
    "--username-env",
    "NEXUS_USERNAME",
    "--password-env",
    "NEXUS_PASSWORD",
  ];
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
    report = { schema: 1, ok: false, error: stderr.trim() || "Mcfpm publish did not emit JSON", candidateSha256 };
  }
  report.candidateSha256 = candidateSha256;
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
    // Preserve the original failure when the publisher arguments themselves are malformed.
  }
  process.stderr.write(message);
  process.exitCode = 2;
}
