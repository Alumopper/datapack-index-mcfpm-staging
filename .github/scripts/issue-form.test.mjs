import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";

const root = path.join(import.meta.dirname, "..", "ISSUE_TEMPLATE");

test("the Wheel submission is a GitHub-recognized issue form", () => {
	const formPath = path.join(root, "new_wheel.yml");
	assert.equal(fs.existsSync(formPath), true);
	assert.equal(fs.existsSync(path.join(root, "new_wheel.yaml")), false);
	const source = fs.readFileSync(formPath, "utf8");
	assert.match(source, /^name: 提交新 Wheel$/m);
	assert.match(source, /^description: .+$/m);
	assert.match(source, /^title: "\[Wheel\] "$/m);
	assert.doesNotMatch(source, /^title:\s*(?:""|''|null)?\s*$/m);
	assert.match(source, /^body:$/m);
	assert.match(source, /<!-- mcfpm-import-template: new_wheel-v1 -->/);
	assert.match(source, /^\s+- type: checkboxes$/m);
});
