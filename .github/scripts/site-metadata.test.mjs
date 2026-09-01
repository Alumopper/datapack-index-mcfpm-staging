import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { test } from "node:test";

import {
  encodeCanonicalSiteMetadata,
  normalizeSiteMetadata,
  siteMetadataRepositoryPath,
} from "./site-metadata-lib.mjs";

const fixture = {
  schema: 1,
  packageId: "io.github.example:demo",
  version: "1.2.3",
  name: "Demo",
  description: "A demo package",
  coverUrl: "https://example.test/cover.png",
  authors: [{
    name: "Example",
    avatarUrl: "https://example.test/avatar.png",
    links: [{ label: "GitHub", url: "https://github.com/example" }],
  }],
  tags: ["utility"],
  gameVersions: ["1.21"],
  dependencyNotes: null,
  detailsMarkdown: "## Usage\n\nDetails.",
  projectUrl: "https://github.com/example/demo",
  legacyPath: "/wheel/resources/demo.html",
};

test("canonical metadata has a deterministic Maven sidecar path", () => {
  const first = encodeCanonicalSiteMetadata(fixture);
  const second = encodeCanonicalSiteMetadata({ ...fixture, tags: [...fixture.tags] });
  assert.equal(first, second);
  assert.equal(
    siteMetadataRepositoryPath(fixture),
    "io/github/example/demo/1.2.3/demo-1.2.3.mcfpm-site.json",
  );
});

test("metadata rejects unsafe URLs and coordinate-independent fields", () => {
  assert.throws(() => normalizeSiteMetadata({ ...fixture, coverUrl: "http://example.test/cover.png" }), /HTTPS/);
  assert.throws(() => normalizeSiteMetadata({ ...fixture, surprise: "field" }), /unknown field/);
  assert.throws(() => normalizeSiteMetadata({ ...fixture, legacyPath: "/../secret.html" }), /legacy path/);
});

test("create script freezes metadata and records its hash in the audit report", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "mcfpm-site-metadata-"));
  try {
    const configPath = path.join(directory, "config.json");
    const outputPath = path.join(directory, "site.json");
    const reportPath = path.join(directory, "report.json");
    fs.writeFileSync(configPath, JSON.stringify({
      package: fixture.packageId,
      version: fixture.version,
      repository: "example/demo",
      site: {
        name: fixture.name,
        description: fixture.description,
        cover: fixture.coverUrl,
        author: "Example",
        authorAvatar: fixture.authors[0].avatarUrl,
        authorSocialLinks: "GitHub: https://github.com/example\nDiscord:",
        tags: "utility, API",
        gameversion: "1.21，1.21.1",
        depends: "None",
        details: fixture.detailsMarkdown,
      },
    }));
    fs.writeFileSync(reportPath, JSON.stringify({ schema: 1, ok: true, data: {} }));
    const script = path.join(import.meta.dirname, "create-site-metadata.mjs");
    const result = spawnSync(process.execPath, [
      script,
      "--config", configPath,
      "--output", outputPath,
      "--report", reportPath,
    ], { encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr);
    const output = fs.readFileSync(outputPath, "utf8");
    const report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
    assert.equal(report.data.siteMetadataSha256, crypto.createHash("sha256").update(output).digest("hex"));
    assert.deepEqual(JSON.parse(output).tags, ["utility", "API"]);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
