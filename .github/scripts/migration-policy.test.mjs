import assert from "node:assert/strict";
import { test } from "node:test";

import { MOD_BLOCKER, assertMigrationPublishable, enforceMigrationPolicy, isModEntry } from "./migration-policy.mjs";

test("recognizes explicit and exact-tag mod entries", () => {
  assert.equal(isModEntry({ contentType: "mod" }), true);
  assert.equal(isModEntry({ site: { tags: ["模组"] } }), true);
  assert.equal(isModEntry({ site: { tags: ["module", "外部软件"] } }), false);
});

test("keeps mods blocked even if version and license blockers are resolved", () => {
  const entry = { coordinate: "io.github.example:mod", contentType: "mod", blockers: [] };
  enforceMigrationPolicy(entry);
  assert.deepEqual(entry.blockers, [MOD_BLOCKER]);
  assert.throws(() => assertMigrationPublishable(entry), /is a mod/);
});
