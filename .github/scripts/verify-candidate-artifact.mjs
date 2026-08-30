#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";

function parseArguments(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value || value.startsWith("--") || result[key]) throw new Error("Invalid candidate verification arguments");
    result[key] = value;
    index += 1;
  }
  if (!result["--candidate"] || !result["--audit-report"]) throw new Error("Candidate and audit report are required");
  return result;
}

try {
  const argumentsObject = parseArguments(process.argv.slice(2));
  const candidate = fs.readFileSync(argumentsObject["--candidate"]);
  const report = JSON.parse(fs.readFileSync(argumentsObject["--audit-report"], "utf8"));
  const expected = report.data?.candidateSha256;
  const actual = crypto.createHash("sha256").update(candidate).digest("hex");
  if (!/^[0-9a-f]{64}$/.test(expected ?? "") || expected !== actual) {
    throw new Error("Downloaded candidate does not match the candidate frozen by the audit job");
  }
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 2;
}
