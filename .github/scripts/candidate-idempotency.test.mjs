import assert from "node:assert/strict";
import crypto from "node:crypto";
import { test } from "node:test";

import {
  candidateDescriptorUrl,
  descriptorMatchesCandidate,
  probeExistingCandidate,
} from "./candidate-idempotency.mjs";

const candidate = {
  schema: 1,
  packageId: "io.github.example:demo",
  version: "2.0.2+0",
  license: "MIT",
  minecraft: "1.21.4+",
  dependencies: [{ package: "io.github.example:base", version: "^1.0.0" }],
  payload: {
    type: "minecraft.datapack",
    classifier: "datapack",
    normalizedSha256: "a".repeat(64),
    normalizedSize: 1234,
  },
  source: {
    kind: "github-release-asset",
    immutableVersion: "release:10/asset:20",
    rawSha256: "b".repeat(64),
    rawSize: 2345,
    revision: "c".repeat(40),
    selectionPath: "/",
    upstreamId: 20,
    finalUrl: "https://release-assets.githubusercontent.com/first-signature",
  },
};

function descriptor(uri = "https://release-assets.githubusercontent.com/second-signature") {
  return {
    schema: 1,
    packageId: candidate.packageId,
    version: candidate.version,
    license: candidate.license,
    minecraft: candidate.minecraft,
    dependencies: candidate.dependencies,
    features: [],
    tool: { consumerProfile: "all" },
    artifacts: [{
      type: candidate.payload.type,
      classifier: candidate.payload.classifier,
      sha256: candidate.payload.normalizedSha256,
      size: candidate.payload.normalizedSize,
      extension: "zip",
      executable: false,
      requires: [],
      source: {
        kind: candidate.source.kind,
        immutableVersion: candidate.source.immutableVersion,
        sha256: candidate.source.rawSha256,
        size: candidate.source.rawSize,
        revision: candidate.source.revision,
        path: candidate.source.selectionPath,
        upstreamId: String(candidate.source.upstreamId),
        redistributionLicense: candidate.license,
        uri,
      },
    }],
  };
}

test("temporary source URI changes do not break release idempotency", () => {
  assert.equal(descriptorMatchesCandidate(candidate, descriptor()), true);
});

test("stable payload or provenance changes still reject the existing coordinate", () => {
  const changedPayload = descriptor();
  changedPayload.artifacts[0].sha256 = "d".repeat(64);
  assert.equal(descriptorMatchesCandidate(candidate, changedPayload), false);

  const changedSource = descriptor();
  changedSource.artifacts[0].source.revision = "e".repeat(40);
  assert.equal(descriptorMatchesCandidate(candidate, changedSource), false);
});

test("descriptor URL encodes SemVer build metadata", () => {
  assert.equal(
    candidateDescriptorUrl(candidate, "https://nexus.example/repository/releases"),
    "https://nexus.example/repository/releases/io/github/example/demo/2.0.2%2B0/demo-2.0.2%2B0.mcfpkg",
  );
});

test("existing equivalent descriptor returns already_present and its exact hash", async () => {
  const bytes = Buffer.from(JSON.stringify(descriptor()));
  const result = await probeExistingCandidate(candidate, "https://nexus.example/releases/", async (url) => (
    new Response(bytes, { status: 200, headers: { "Content-Length": String(bytes.length) } })
  ));
  assert.equal(result.status, "already_present");
  assert.equal(result.descriptorSha256, crypto.createHash("sha256").update(bytes).digest("hex"));
});

test("404 permits first publication but repository errors do not", async () => {
  const missing = await probeExistingCandidate(candidate, "https://nexus.example/releases/", async () => (
    new Response(null, { status: 404 })
  ));
  assert.equal(missing.status, "missing");

  await assert.rejects(
    probeExistingCandidate(candidate, "https://nexus.example/releases/", async () => (
      new Response(null, { status: 503 })
    )),
    /HTTP 503/,
  );
});

test("an existing descriptor with different stable content is rejected", async () => {
  const changed = descriptor();
  changed.artifacts[0].source.sha256 = "f".repeat(64);
  await assert.rejects(
    probeExistingCandidate(candidate, "https://nexus.example/releases/", async () => (
      new Response(JSON.stringify(changed), { status: 200 })
    )),
    /different stable content/,
  );
});
