import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";

import { prepareManifest } from "./prepare-legacy-migration-round-2.mjs";

test("round two pins current commits and records direct MIT attestations", () => {
  const manifestPath = path.join(import.meta.dirname, "..", "migrations", "legacy-wheel-v1.json");
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  const prepared = prepareManifest(structuredClone(manifest));
  const entries = new Map(prepared.entries.map((entry) => [entry.coordinate, entry]));

  assert.equal(prepared.readyCount, 43);
  assert.equal(prepared.blockedCount, 12);
  assert.deepEqual(entries.get("io.github.dartcat25:cem-s").source, {
    type: "github-archive",
    repository: "DartCat25/CEM-S",
    ref: "fb82f20698e8972f241574a9390413f385c8bddb",
  });
  assert.equal(entries.get("io.github.alumopper:floating-ui").license, "MIT");
  assert.match(entries.get("io.github.xiaodou8593:math3.1").licenseBasisUrl, /legacy-license-attestations/);
  assert.equal(entries.get("io.github.xiaodou8593:math3.0_lalib").status, "ready-for-cli-audit");
  assert.equal(entries.get("io.github.dartcat25:cem-s").subdir, "1.20.5");
  assert.equal(entries.get("io.github.dahesor:leopard-cat").status, "blocked");
  assert.match(entries.get("io.github.dahesor:leopard-cat").blockers[0], /root-pack-cannot-be-selected/);
  assert.equal(entries.get("io.github.dahesor:dabsu").status, "ready-for-cli-audit");
  assert.equal(entries.get("io.github.dahesor:dabsu").rootPack, true);
  assert.equal(entries.get("io.github.dahesor:dabsu").minecraft, "1.21.11");
  assert.deepEqual(entries.get("io.github.windwavessea:simple-npc").source, {
    type: "github-release-asset",
    repository: "WindWavesSea/Simple-NPC",
    ref: "V1.1.0",
    asset: "Simple_NPC_Data_Pack_V1.1.0.zip",
  });
  assert.equal(entries.get("io.github.windwavessea:simple-npc").version, "1.1.0");
  assert.equal(entries.get("io.github.windwavessea:simple-npc").status, "ready-for-cli-audit");
  assert.equal(entries.get("io.github.windwavessea:simple-npc").packMetadataRepair, "simple-npc-description-comma-v1");
  assert.equal(entries.get("io.github.windwavessea:simple-npc").expectedSha256, "437a858138f03637667c761cfe3ce61323bce660c625ff9a18f9bc43318b6fd2");
  assert.equal(entries.get("io.github.anvil-dev:anisum").status, "blocked");
  assert.equal(entries.get("io.github.anvil-dev:anisum").contentType, "mod");
  assert.ok(entries.get("io.github.anvil-dev:anisum").blockers.includes("unsupported-content-type:mod"));
});
