#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { enforceMigrationPolicy } from "./migration-policy.mjs";

const CURRENT_COMMITS = {
  "io.github.dartcat25:cem-s": ["DartCat25/CEM-S", "fb82f20698e8972f241574a9390413f385c8bddb"],
  "io.github.4tubborn:sbox": ["4tubborn/sbox", "032af44081a987ab7c303777b0043f15cf0113b5"],
  "io.github.cmdred:forceloadlib": ["CMDred/ForceloadLib", "ab4e420bda11f8e0729393599f979da512270d1c"],
  "io.github.kaer-3058:large_number": ["kaer-3058/large_number", "a7dcb48b2839eff3f3fc922e30a1172de95b665c"],
  "io.github.cmdred:per-player-displays": ["CMDred/Per-Player-Displays", "90c398ceff1cc88fb0b40c7dd8a2ff50db65dc09"],
  "io.github.cmdred:scorefixer": ["CMDred/ScoreFixer", "83663b25052caf956362a0105563cd96035f43a3"],
  "io.github.thesalts:text_effects": ["TheSalts/Text_Effects", "ea922a1fc11559108afcfd2f97c754e96b4b6d55"],
  "io.github.cmdred:timelib": ["CMDred/TimeLib", "47d39f3a0d062653986c481c1dc8ddf6f0fe32d7"],
  "io.github.rain156:traditional-to-simplified": ["Rain156/Traditional-to-Simplified", "71338ae366cedbd32ec79f5fac1990aee5b746e4"],
  "io.github.halbfettkaese:wireframedisplay": ["HalbFettKaese/WireframeDisplay", "633d202becfd2b4d856c5477e282c562f06f6688"],
  "io.github.dahesor:leopard-cat": ["Dahesor/Leopard-Cat", "2d23d5cec5fcddd76570e090ddfea6cb1d2cc38b"],
  "io.github.dahesor:dnt-dahesor-nbt-transformer": ["Dahesor/DNT-Dahesor-NBT-Transformer", "ee7e399cebc2c76d227729368e0af6949af93bb6"],
  "io.github.gibbsly:ehid": ["gibbsly/ehid", "9cfed3223157a1a1d970b06e9d0db12d283378c7"],
  "io.github.triton365:fast_bitwise_ops": ["Triton365/fast_bitwise_ops", "7e6c98e5dfeed47977420b16fdc981ee5c10a7e6"],
  "io.github.gibbsly:gm": ["gibbsly/gm", "d68b6c9fd7fd56a2b9c5ebcb9c6fe0651b7f73cd"],
  "io.github.godlander:objmc": ["Godlander/objmc", "787125519e640ca08943475e8119aaef50b9e5d2"],
  "io.github.xiaodou8593:math3.0_lalib": ["xiaodou8593/math3.0_lalib", "e114da8b2d2a93fdaa2679dc4dbaf8884967d07d"],
};

const ATTESTATION_URL = "https://github.com/Alumopper/datapack-index-mcfpm-staging/blob/master/.github/migrations/legacy-license-attestations.md";
const MIT_ATTESTATIONS = {
  "io.github.alumopper:floating-ui": `${ATTESTATION_URL}#floating-ui`,
  "io.github.xiaodou8593:math3.0_lalib": `${ATTESTATION_URL}#xiaodou8593-math-libraries`,
  "io.github.xiaodou8593:math3.1": `${ATTESTATION_URL}#xiaodou8593-math-libraries`,
  "io.github.xiaodou8593:math3.1_dslib": `${ATTESTATION_URL}#xiaodou8593-math-libraries`,
  "io.github.xiaodou8593:math3.1_gelib": `${ATTESTATION_URL}#xiaodou8593-math-libraries`,
  "io.github.xiaodou8593:timelist": `${ATTESTATION_URL}#xiaodou8593-math-libraries`,
};

const SUBDIRECTORIES = {
  "io.github.dartcat25:cem-s": "1.20.5",
};

const STRUCTURAL_BLOCKERS = {
  "io.github.dahesor:leopard-cat": "pinned-cli:root-pack-cannot-be-selected-when-dependency-pack-is-present",
};

const ROOT_PACKS = {
  "io.github.dahesor:dabsu": {
    repository: "Dahesor/DaBsu-Batch-Spawner-Utils",
    auditSubdir: "version_warning",
    expectedSha256: "1b99c13ebc5f400b673d26f75b8eb3e1515bb97bb8a5f67d3d5fc36794497e79",
    minecraft: "1.21.11",
  },
};

function fail(message) {
  throw new Error(message);
}

function parseArguments(argv) {
  if (argv.length !== 2 || argv[0] !== "--manifest" || !argv[1]) fail("Usage: --manifest PATH");
  return path.resolve(argv[1]);
}

function removeBlocker(entry, blocker) {
  entry.blockers = entry.blockers.filter((value) => value !== blocker);
}

export function prepareManifest(manifest) {
  if (!manifest || manifest.schema !== 1 || !Array.isArray(manifest.entries)) fail("Migration manifest is invalid");
  const entries = new Map(manifest.entries.map((entry) => [entry.coordinate, entry]));

  for (const [coordinate, [repository, commit]] of Object.entries(CURRENT_COMMITS)) {
    const entry = entries.get(coordinate);
    if (!entry) fail(`Migration entry ${coordinate} does not exist`);
    entry.source = { type: "github-archive", repository, ref: commit };
    removeBlocker(entry, "missing-exact-version-tag-or-release");
  }

  for (const [coordinate, licenseBasisUrl] of Object.entries(MIT_ATTESTATIONS)) {
    const entry = entries.get(coordinate);
    if (!entry) fail(`Migration entry ${coordinate} does not exist`);
    entry.license = "MIT";
    entry.licenseBasisUrl = licenseBasisUrl;
    removeBlocker(entry, "missing-spdx-license");
  }

  for (const [coordinate, subdir] of Object.entries(SUBDIRECTORIES)) {
    entries.get(coordinate).subdir = subdir;
  }

  for (const [coordinate, blocker] of Object.entries(STRUCTURAL_BLOCKERS)) {
    const entry = entries.get(coordinate);
    entry.subdir = null;
    if (!entry.blockers.includes(blocker)) entry.blockers.push(blocker);
  }

  for (const [coordinate, rootPack] of Object.entries(ROOT_PACKS)) {
    const entry = entries.get(coordinate);
    if (!entry) fail(`Migration entry ${coordinate} does not exist`);
    entry.source.repository = rootPack.repository;
    entry.subdir = rootPack.auditSubdir;
    entry.rootPack = true;
    entry.expectedSha256 = rootPack.expectedSha256;
    entry.minecraft = rootPack.minecraft;
    entry.site.gameVersions = [rootPack.minecraft];
    entry.site.projectUrl = `https://github.com/${rootPack.repository}`;
    entry.site.details = entry.site.details.replaceAll("https://github.com/Dahesor/DaBsu", entry.site.projectUrl);
    entry.discovery.githubUrls = [entry.site.projectUrl];
    removeBlocker(entry, "pinned-cli:root-pack-is-ambiguous-with-version-warning-pack");
  }

  for (const entry of manifest.entries) {
    enforceMigrationPolicy(entry);
    entry.status = entry.blockers.length ? "blocked" : "ready-for-cli-audit";
  }
  manifest.readyCount = manifest.entries.filter((entry) => entry.status === "ready-for-cli-audit").length;
  manifest.blockedCount = manifest.entries.length - manifest.readyCount;
  manifest.updatedAt = "2026-09-02T00:00:00Z";
  if (manifest.readyCount !== 42 || manifest.blockedCount !== 13) {
    fail(`Expected 42 ready and 13 blocked entries, got ${manifest.readyCount} and ${manifest.blockedCount}`);
  }
  return manifest;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const manifestPath = parseArguments(process.argv.slice(2));
  const manifest = prepareManifest(JSON.parse(fs.readFileSync(manifestPath, "utf8")));
  const temporary = `${manifestPath}.${process.pid}.tmp`;
  fs.writeFileSync(temporary, `${JSON.stringify(manifest, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
  fs.renameSync(temporary, manifestPath);
  process.stdout.write(`Prepared ${manifest.readyCount} entries; ${manifest.blockedCount} remain static-only.\n`);
}
