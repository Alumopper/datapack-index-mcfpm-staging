#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

import { encodeCanonicalSiteMetadata, siteMetadataSha256 } from "./site-metadata-lib.mjs";

function fail(message) {
  throw new Error(message);
}

function parseArguments(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value || value.startsWith("--") || result[key]) fail("Invalid site metadata arguments");
    result[key] = value;
    index += 1;
  }
  for (const key of ["--config", "--output", "--report"]) if (!result[key]) fail(`Missing ${key}`);
  return result;
}

function atomicWrite(output, content, mode = 0o600) {
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const temporary = path.join(path.dirname(output), `.${path.basename(output)}.${process.pid}.tmp`);
  fs.writeFileSync(temporary, content, { encoding: "utf8", mode });
  fs.renameSync(temporary, output);
}

function splitList(value) {
  if (!value) return [];
  return [...new Set(String(value).split(/[\n,，、]+/).map((entry) => entry.trim()).filter(Boolean))];
}

function parseLinks(value) {
  if (!value) return [];
  const links = [];
  for (const line of String(value).split(/\r?\n/)) {
    if (!line.trim()) continue;
    const separator = line.indexOf(":");
    if (separator < 1) fail("Author links must use LABEL: HTTPS_URL");
    const label = line.slice(0, separator).trim();
    const url = line.slice(separator + 1).trim();
    if (!url) continue;
    links.push({ label, url });
  }
  return links;
}

try {
  const args = parseArguments(process.argv.slice(2));
  const config = JSON.parse(fs.readFileSync(args["--config"], "utf8"));
  const site = config.site;
  if (!site || typeof site !== "object") fail("Import config has no site metadata");
  const projectUrl = site.link || (config.repository ? `https://github.com/${config.repository}` : null);
  const metadata = {
    schema: 1,
    packageId: config.package,
    version: config.version,
    name: site.name,
    description: site.description,
    coverUrl: site.cover || null,
    authors: [{
      name: site.author,
      avatarUrl: site.authorAvatar || null,
      links: parseLinks(site.authorSocialLinks),
    }],
    tags: splitList(site.tags),
    gameVersions: splitList(site.gameversion),
    dependencyNotes: site.depends || null,
    detailsMarkdown: site.details,
    projectUrl,
    legacyPath: site.legacyPath || null,
  };
  const encoded = encodeCanonicalSiteMetadata(metadata);
  atomicWrite(args["--output"], encoded);
  const report = JSON.parse(fs.readFileSync(args["--report"], "utf8"));
  report.data = { ...(report.data ?? {}), siteMetadataSha256: siteMetadataSha256(encoded) };
  atomicWrite(args["--report"], `${JSON.stringify(report, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 2;
}
