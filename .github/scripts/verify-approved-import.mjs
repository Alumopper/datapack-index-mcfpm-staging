#!/usr/bin/env node

import fs from "node:fs";

function fail(message) {
  throw new Error(message);
}

const eventPath = process.env.GITHUB_EVENT_PATH;
const token = process.env.GITHUB_TOKEN;
const repository = process.env.GITHUB_REPOSITORY;
if (!eventPath || !token || !repository) fail("GitHub event, token, and repository context are required");

const event = JSON.parse(fs.readFileSync(eventPath, "utf8"));
if (event.action !== "labeled" || event.label?.name !== "approved-import" || event.issue?.state !== "open") {
  fail("The issue event is not an open approved-import transition");
}
const actor = event.sender?.login;
if (!actor || !/^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$/.test(actor)) fail("The labeler is invalid");

const endpoint = `https://api.github.com/repos/${repository}/collaborators/${encodeURIComponent(actor)}/permission`;
const response = await fetch(endpoint, {
  headers: {
    Accept: "application/vnd.github+json",
    Authorization: `Bearer ${token}`,
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "datapack-index-mcfpm-audit",
  },
});
if (!response.ok) fail(`Unable to verify the labeler's repository permission (HTTP ${response.status})`);
const permission = await response.json();
if (permission.permission !== "admin") fail("Only a repository administrator may add approved-import");
