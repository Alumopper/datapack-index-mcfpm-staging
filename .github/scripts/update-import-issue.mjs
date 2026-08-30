#!/usr/bin/env node

import fs from "node:fs";

function fail(message) {
  throw new Error(message);
}

function parseArguments(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value || value.startsWith("--") || result[key]) fail("Invalid issue updater arguments");
    result[key] = value;
    index += 1;
  }
  if (!["audit-passed", "audit-failed", "publish-passed", "publish-failed"].includes(result["--state"])) {
    fail("--state must be audit-passed, audit-failed, publish-passed, or publish-failed");
  }
  return result;
}

function markdown(value) {
  return String(value ?? "").replaceAll("\\", "\\\\").replaceAll("|", "\\|").replaceAll("`", "'").replace(/[\r\n]+/g, " ").trim();
}

function html(value) {
  return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll("`", "'");
}

const argumentsObject = parseArguments(process.argv.slice(2));
const token = process.env.GITHUB_TOKEN;
const repository = process.env.GITHUB_REPOSITORY;
const issueNumber = process.env.ISSUE_NUMBER;
if (!token || !repository || !/^[0-9]+$/.test(issueNumber ?? "")) fail("GitHub issue context is required");

const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, "utf8"));
const runUrl = process.env.GITHUB_RUN_URL || `https://github.com/${repository}/actions/runs/${process.env.GITHUB_RUN_ID ?? ""}`;
const state = argumentsObject["--state"];
const reportPath = argumentsObject["--report"];
const errorPath = argumentsObject["--error"];
let report = {};
if (reportPath && fs.existsSync(reportPath)) {
  try {
    report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
  } catch {
    report = {};
  }
}
const errorText = errorPath && fs.existsSync(errorPath) ? fs.readFileSync(errorPath, "utf8").slice(-6000) : "";
const data = report.data ?? {};
const configPath = process.env.IMPORT_PARAMS;
let config = {};
if (configPath && fs.existsSync(configPath)) {
  try {
    config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  } catch {
    config = {};
  }
}

const baseUrl = `https://api.github.com/repos/${repository}`;
const headers = {
  Accept: "application/vnd.github+json",
  Authorization: `Bearer ${token}`,
  "X-GitHub-Api-Version": "2022-11-28",
  "User-Agent": "datapack-index-mcfpm-audit",
};

async function api(method, suffix, body, allowNotFound = false) {
  const response = await fetch(`${baseUrl}${suffix}`, {
    method,
    headers: { ...headers, ...(body ? { "Content-Type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (allowNotFound && response.status === 404) return;
  if (!response.ok) fail(`GitHub API ${method} ${suffix} failed with HTTP ${response.status}`);
}

async function addLabels(labels) {
  await api("POST", `/issues/${issueNumber}/labels`, { labels });
}

async function removeLabel(label) {
  await api("DELETE", `/issues/${issueNumber}/labels/${encodeURIComponent(label)}`, undefined, true);
}

async function comment(body) {
  await api("POST", `/issues/${issueNumber}/comments`, { body });
}

if (state === "audit-passed") {
  await removeLabel("mcfpm-audit-failed");
  await addLabels(["mcfpm-audited"]);
  const source = data.finalUrl ?? data.source ?? config.url ?? config.repository ?? "unknown";
  const message = [
    "### Mcfpm audit passed",
    "",
    `| Field | Value |`,
    `| --- | --- |`,
    `| Coordinate | \`${markdown(data.package ?? config.package)}@${markdown(data.version ?? config.version)}\` |`,
    `| Source type | ${markdown(config.sourceType)} |`,
    `| Final URL | ${markdown(source)} |`,
    `| License basis | ${markdown(config.licenseBasisUrl)} |`,
    `| Raw SHA-256 | \`${markdown(data.rawSha256)}\` |`,
    `| Raw size | ${markdown(data.rawSize)} bytes |`,
    `| Payload | ${markdown(data.payloadType)} / ${markdown(data.classifier)}; ${markdown(data.normalizedSha256)} / ${markdown(data.normalizedSize)} bytes |`,
    `| Selection | \`${markdown(data.selectionPath)}\` |`,
    `| Candidate artifact | [workflow run](${runUrl}) |`,
    "",
    "The frozen candidate is ready for the `nexus-production` Environment approval.",
  ].join("\n");
  await comment(message);
} else if (state === "audit-failed") {
  await removeLabel("approved-import");
  await removeLabel("mcfpm-audited");
  await addLabels(["mcfpm-audit-failed"]);
  await comment([
    "### Mcfpm audit failed",
    "",
    `The candidate was not frozen or published. See [workflow run](${runUrl}).`,
    "",
    "```text",
    html(errorText || "The audit job failed before Mcfpm emitted a diagnostic."),
    "```",
  ].join("\n"));
} else if (state === "publish-passed") {
  await removeLabel("approved-import");
  await removeLabel("mcfpm-audited");
  await removeLabel("mcfpm-audit-failed");
  await removeLabel("mcfpm-publish-failed");
  await addLabels(["mcfpm-published"]);
  await comment([
    "### Mcfpm publication completed",
    "",
    `| Coordinate | \`${markdown(data.package)}@${markdown(data.version)}\` |`,
    `| Repository | ${markdown(data.repository)} |`,
    `| Descriptor SHA-256 | \`${markdown(data.descriptorSha256)}\` |`,
    `| Candidate SHA-256 | \`${markdown(report.candidateSha256)}\` |`,
    `| Workflow run | [open run](${runUrl}) |`,
    "",
    "The Issue remains open for the existing site collection workflow.",
  ].join("\n"));
} else {
  await removeLabel("mcfpm-audited");
  await removeLabel("approved-import");
  await addLabels(["mcfpm-publish-failed"]);
  await comment([
    "### Mcfpm publication failed",
    "",
    `The Nexus publication did not complete. See [workflow run](${runUrl}).`,
    "",
    "```text",
    html(errorText || report.error || "Mcfpm did not emit a publication diagnostic."),
    "```",
  ].join("\n"));
}
