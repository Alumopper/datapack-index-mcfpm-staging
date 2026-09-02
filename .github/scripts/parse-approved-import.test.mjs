import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { test } from "node:test";

const parser = path.join(import.meta.dirname, "parse-approved-import.mjs");

const labels = [
  ["前置名称", "Example Pack"],
  ["版本", "1.2.3"],
  ["前置图标链接", ""],
  ["作者", "Example"],
  ["作者头像的链接", "https://example.test/avatar.png"],
  ["作者链接（每行一个）", "Github: https://github.com/example"],
  ["简介", "A fixture"],
  ["Github 仓库链接（可选）", "https://github.com/example/pack"],
  ["支持的游戏版本", "1.21"],
  ["标签", "test"],
  ["依赖项", ""],
  ["详情", "Details"],
  ["可发布内容类型", "数据包"],
  ["来源类型", "普通 HTTPS ZIP"],
  ["GitHub 仓库", ""],
  ["GitHub tag/commit", ""],
  ["GitHub Release asset", ""],
  ["普通 HTTPS ZIP 下载 URL", "https://downloads.example.test/pack.zip"],
  ["Maven 坐标（GROUP:NAME）", "example:pack"],
  ["SPDX 许可证", "MIT"],
  ["许可证/再分发依据链接", "https://example.test/license"],
  ["预期 SHA-256（可选）", ""],
  ["包内子目录（可选）", "packs/example"],
  ["嵌套 ZIP 路径（可选）", ""],
  ["Minecraft 版本范围（可选）", ">=1.21"],
  ["Mcfpm 依赖（每行一个，可选）", "example:z@^1.0.0\nexample:a@1.0.0"],
  ["再分发确认", "- [x] I confirm"],
];

function body(overrides = {}) {
  const values = new Map(labels);
  for (const [key, value] of Object.entries(overrides)) values.set(key, value);
  return [
    "<!-- mcfpm-import-template: new_wheel-v1 -->",
    ...[...values].flatMap(([label, value]) => [`### ${label}`, "", value]),
  ].join("\n");
}

function run(overrides = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "mcfpm-issue-parser-"));
  const event = path.join(directory, "event.json");
  const output = path.join(directory, "params.json");
  fs.writeFileSync(event, JSON.stringify({
    action: "labeled",
    issue: { state: "open", body: body(overrides) },
    label: { name: "approved-import" },
  }));
  const result = spawnSync(process.execPath, [parser, "--event", event, "--output", output], { encoding: "utf8" });
  return { result, output };
}

test("parses URL, release asset, and archive source fixtures", () => {
  const cases = [
    {
      "来源类型": "普通 HTTPS ZIP",
      "普通 HTTPS ZIP 下载 URL": "https://downloads.example.test/pack.zip",
      "GitHub 仓库": "",
      "GitHub tag/commit": "",
      "GitHub Release asset": "",
    },
    {
      "来源类型": "GitHub Release asset",
      "普通 HTTPS ZIP 下载 URL": "",
      "GitHub 仓库": "Example/Pack",
      "GitHub tag/commit": "v1.2.3",
      "GitHub Release asset": "pack.zip",
    },
    {
      "来源类型": "GitHub tag/commit archive",
      "普通 HTTPS ZIP 下载 URL": "",
      "GitHub 仓库": "Example/Pack",
      "GitHub tag/commit": "release/1.2.3",
      "GitHub Release asset": "",
    },
  ];
  for (const fixture of cases) {
    const { result, output } = run(fixture);
    assert.equal(result.status, 0, result.stderr);
    const parsed = JSON.parse(fs.readFileSync(output, "utf8"));
    assert.equal(parsed.package, "example:pack");
    assert.equal(parsed.contentType, "datapack");
    assert.deepEqual(parsed.dependencies, ["example:a@1.0.0", "example:z@^1.0.0"]);
  }
});

test("rejects missing form fields and shell-like dependency data", () => {
  assert.notEqual(run({ "Maven 坐标（GROUP:NAME）": "example:pack;touch /tmp/pwned" }).result.status, 0);
  assert.notEqual(run({ "许可证/再分发依据链接": "http://example.test/license" }).result.status, 0);
  assert.notEqual(run({ "可发布内容类型": "模组" }).result.status, 0);
  const missingMarker = run();
  const directory = path.dirname(missingMarker.output);
  const event = path.join(directory, "event.json");
  fs.writeFileSync(event, JSON.stringify({
    action: "labeled",
    issue: { state: "open", body: body().replace("mcfpm-import-template: new_wheel-v1", "edited") },
    label: { name: "approved-import" },
  }));
  const result = spawnSync(process.execPath, [parser, "--event", event, "--output", missingMarker.output], { encoding: "utf8" });
  assert.notEqual(result.status, 0);
});
