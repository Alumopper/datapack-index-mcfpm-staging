#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const TEMPLATE_MARKER = "<!-- mcfpm-import-template: new_wheel-v1 -->";
const HEADINGS = [
  "前置名称",
  "版本",
  "前置图标链接",
  "作者",
  "作者头像的链接",
  "作者链接（每行一个）",
  "简介",
  "Github 仓库链接（可选）",
  "支持的游戏版本",
  "标签",
  "依赖项",
  "详情",
  "可发布内容类型",
  "来源类型",
  "GitHub 仓库",
  "GitHub tag/commit",
  "GitHub Release asset",
  "普通 HTTPS ZIP 下载 URL",
  "Maven 坐标（GROUP:NAME）",
  "SPDX 许可证",
  "许可证/再分发依据链接",
  "预期 SHA-256（可选）",
  "包内子目录（可选）",
  "嵌套 ZIP 路径（可选）",
  "Minecraft 版本范围（可选）",
  "Mcfpm 依赖（每行一个，可选）",
  "再分发确认",
];
const REQUIRED_HEADINGS = new Set([
  "前置名称",
  "版本",
  "作者",
  "作者头像的链接",
  "简介",
  "支持的游戏版本",
  "标签",
  "详情",
  "可发布内容类型",
  "来源类型",
  "Maven 坐标（GROUP:NAME）",
  "SPDX 许可证",
  "许可证/再分发依据链接",
  "再分发确认",
]);
const PACKAGE_ID = /^[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?:[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?$/;
const SHA256 = /^[0-9a-f]{64}$/i;
const SEMVER = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
const ENVIRONMENT = /^[A-Za-z_][A-Za-z0-9_]*$/;

function fail(message) {
  throw new Error(message);
}

function parseArguments(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (!argument.startsWith("--")) fail(`Unexpected argument: ${argument}`);
    const key = argument.slice(2);
    if (!key || result[key] !== undefined) fail(`Duplicate argument: --${key}`);
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) fail(`Missing value for --${key}`);
    result[key] = value;
    index += 1;
  }
  if (!result.event || !result.output || Object.keys(result).some((key) => !["event", "output"].includes(key))) {
    fail("Usage: parse-approved-import.mjs --event EVENT_JSON --output PARAMS_JSON");
  }
  return result;
}

function readHeadings(body) {
  const values = new Map();
  const lines = body.replaceAll("\r\n", "\n").split("\n");
  for (let index = 0; index < lines.length; index += 1) {
    const match = /^### ([^\n]+)$/.exec(lines[index]);
    if (!match) continue;
    const label = match[1].trim();
    if (values.has(label)) fail(`Issue contains duplicate field heading: ${label}`);
    let end = index + 1;
    while (end < lines.length && !/^### [^\n]+$/.test(lines[end])) end += 1;
    values.set(label, lines.slice(index + 1, end).join("\n").trim());
    index = end - 1;
  }
  return values;
}

function value(fields, label, required = false) {
  const result = fields.get(label) ?? "";
  if (required && result.length === 0) fail(`Issue field is empty: ${label}`);
  return result;
}

function optional(fields, label) {
  const result = value(fields, label);
  return result || null;
}

function parseSemVer(valueToParse) {
  const match = SEMVER.exec(valueToParse);
  if (!match) fail("版本必须是发布版 SemVer，例如 1.2.3");
  const prerelease = match[4] ?? "";
  if (prerelease.split(".").some((part) => /^[0-9]+$/.test(part) && part.length > 1 && part.startsWith("0"))) {
    fail("版本的数字预发布标识不能有前导零");
  }
  if (/(?:^|\.)snapshot(?:$|\.)/i.test(prerelease)) fail("不允许 snapshot 版本");
  return valueToParse;
}

function requireHttps(valueToParse, label) {
  let parsed;
  try {
    parsed = new URL(valueToParse);
  } catch {
    fail(`${label} 必须是 HTTPS URL`);
  }
  if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) {
    fail(`${label} 必须是公开 HTTPS URL，且不能包含认证信息`);
  }
  return parsed.toString();
}

function selector(valueToParse, label) {
  const normalized = valueToParse.trim().replaceAll("\\", "/").replace(/^\/+|\/+$/g, "");
  if (!normalized || normalized.split("/").some((part) => !part || part === "." || part === "..") || /^[A-Za-z]:/.test(normalized)) {
    fail(`${label} 必须是安全的相对 ZIP 路径`);
  }
  return normalized;
}

function parseDependencies(valueToParse) {
  if (!valueToParse) return [];
  return valueToParse
    .split("\n")
    .map((line) => line.trim().replace(/^-\s+/, ""))
    .filter(Boolean)
    .map((declaration) => {
      const separator = declaration.lastIndexOf("@");
      if (separator <= 0 || separator === declaration.length - 1) fail("Mcfpm 依赖必须使用 GROUP:NAME@REQUIREMENT");
      const packageId = declaration.slice(0, separator);
      const requirement = declaration.slice(separator + 1).trim();
      if (!PACKAGE_ID.test(packageId) || !requirement || /[\[\](),;|`$\\]/.test(requirement)) {
        fail("Mcfpm 依赖包含非法坐标或版本范围");
      }
      return `${packageId}@${requirement}`;
    })
    .sort();
}

function parseIssue(event) {
  if (event.action !== "labeled" || event.issue?.state !== "open" || event.label?.name !== "approved-import") {
    fail("This workflow only accepts an open issue when approved-import is added");
  }
  const body = event.issue.body;
  if (typeof body !== "string" || !body.includes(TEMPLATE_MARKER)) fail("Issue is not the new_wheel-v1 form");
  const fields = readHeadings(body);
  const known = new Set(HEADINGS);
  for (const heading of fields.keys()) if (!known.has(heading)) fail(`Unknown issue field heading: ${heading}`);
  for (const heading of REQUIRED_HEADINGS) {
    if (!fields.has(heading)) fail(`Issue is missing field heading: ${heading}`);
  }

  const sourceTypeValue = value(fields, "来源类型", true);
  const sourceType = {
    "GitHub Release asset": "github-release-asset",
    "GitHub tag/commit archive": "github-archive",
    "普通 HTTPS ZIP": "url",
  }[sourceTypeValue];
  if (!sourceType) fail("来源类型无效");
  const contentTypeValue = value(fields, "可发布内容类型", true);
  const contentType = {
    "数据包": "datapack",
    "资源包": "resourcepack",
  }[contentTypeValue];
  if (!contentType) fail("可发布内容类型无效；模组不能上传 Nexus");

  const githubRepository = optional(fields, "GitHub 仓库");
  const githubRef = optional(fields, "GitHub tag/commit");
  const githubAsset = optional(fields, "GitHub Release asset");
  const sourceUrl = optional(fields, "普通 HTTPS ZIP 下载 URL");
  if (sourceType === "url") {
    if (!sourceUrl) fail("普通 HTTPS ZIP 来源必须填写下载 URL");
    if (githubRepository || githubRef || githubAsset) fail("普通 HTTPS ZIP 来源不能填写 GitHub 字段");
  } else {
    if (!githubRepository || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(githubRepository) || githubRepository.includes("..")) {
      fail("GitHub 仓库必须使用 OWNER/REPOSITORY");
    }
    if (!githubRef || /[\u0000-\u001f\u007f\s]/.test(githubRef) || githubRef.length > 256) fail("GitHub tag/commit 无效");
    if (sourceType === "github-release-asset") {
      if (!githubAsset || githubAsset.includes("/") || githubAsset.includes("\\") || !githubAsset.toLowerCase().endsWith(".zip")) {
        fail("Release asset 必须是精确的 ZIP 文件名");
      }
    } else if (githubAsset) {
      fail("GitHub archive 来源不能填写 Release asset");
    }
    if (sourceUrl) fail("GitHub 来源不能填写普通 HTTPS ZIP URL");
  }

  const expectedSha256Value = optional(fields, "预期 SHA-256（可选）");
  if (expectedSha256Value && !SHA256.test(expectedSha256Value)) fail("预期 SHA-256 必须是 64 位十六进制");
  const packageSubdirValue = optional(fields, "包内子目录（可选）");
  const nestedZipValue = optional(fields, "嵌套 ZIP 路径（可选）");
  const packageSubdir = packageSubdirValue ? selector(packageSubdirValue, "包内子目录") : null;
  const nestedZip = nestedZipValue ? selector(nestedZipValue, "嵌套 ZIP 路径") : null;
  const redistribution = value(fields, "再分发确认", true);
  if (!/\[[xX]\]/.test(redistribution) || /\[[ ]\]/.test(redistribution)) fail("必须勾选再分发确认");

  const packageId = value(fields, "Maven 坐标（GROUP:NAME）", true);
  if (!PACKAGE_ID.test(packageId)) fail("Maven 坐标必须是小写 GROUP:NAME");
  const version = parseSemVer(value(fields, "版本", true));
  const license = value(fields, "SPDX 许可证", true);
  if (!/^[A-Za-z0-9][A-Za-z0-9.+-]*(?:\s+WITH\s+[A-Za-z0-9][A-Za-z0-9.+-]*)?$/.test(license)) fail("SPDX 许可证标识符格式无效");
  const licenseBasisUrl = requireHttps(value(fields, "许可证/再分发依据链接", true), "许可证依据链接");
  const minecraft = optional(fields, "Minecraft 版本范围（可选）");
  if (minecraft && /[\u0000-\u001f\u007f]/.test(minecraft)) fail("Minecraft 版本范围包含控制字符");

  return {
    template: "new_wheel-v1",
    sourceType,
    contentType,
    repository: githubRepository,
    ref: githubRef,
    asset: githubAsset,
    url: sourceUrl ? requireHttps(sourceUrl, "来源 URL") : null,
    package: packageId,
    version,
    license,
    licenseBasisUrl,
    expectedSha256: expectedSha256Value?.toLowerCase() ?? null,
    subdir: packageSubdir,
    nestedZip,
    minecraft,
    dependencies: parseDependencies(value(fields, "Mcfpm 依赖（每行一个，可选）")),
    site: {
      name: value(fields, "前置名称", true),
      version,
      cover: optional(fields, "前置图标链接"),
      author: value(fields, "作者", true),
      authorAvatar: value(fields, "作者头像的链接", true),
      authorSocialLinks: optional(fields, "作者链接（每行一个）"),
      description: value(fields, "简介", true),
      link: optional(fields, "Github 仓库链接（可选）"),
      gameversion: value(fields, "支持的游戏版本", true),
      tags: value(fields, "标签", true),
      depends: optional(fields, "依赖项"),
      details: value(fields, "详情", true),
    },
  };
}

function atomicWrite(output, content) {
  const directory = path.dirname(output);
  fs.mkdirSync(directory, { recursive: true });
  const temporary = path.join(directory, `.${path.basename(output)}.${process.pid}.tmp`);
  fs.writeFileSync(temporary, content, { encoding: "utf8", mode: 0o600 });
  fs.renameSync(temporary, output);
}

try {
  const argumentsObject = parseArguments(process.argv.slice(2));
  const event = JSON.parse(fs.readFileSync(argumentsObject.event, "utf8"));
  const parsed = parseIssue(event);
  atomicWrite(argumentsObject.output, `${JSON.stringify(parsed, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 2;
}
