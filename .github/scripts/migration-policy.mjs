export const MOD_BLOCKER = "unsupported-content-type:mod";

const MOD_TAGS = new Set(["mod", "mods", "模组"]);

export function isModEntry(entry) {
  if (!entry || typeof entry !== "object") return false;
  if (String(entry.contentType || "").trim().toLowerCase() === "mod") return true;
  const tags = Array.isArray(entry.site?.tags) ? entry.site.tags : [];
  return tags.some((tag) => MOD_TAGS.has(String(tag).trim().toLowerCase()));
}

export function enforceMigrationPolicy(entry) {
  if (!Array.isArray(entry?.blockers)) throw new Error("Migration entry has no blocker list");
  if (isModEntry(entry) && !entry.blockers.includes(MOD_BLOCKER)) entry.blockers.push(MOD_BLOCKER);
  return entry;
}

export function assertMigrationPublishable(entry) {
  if (isModEntry(entry)) throw new Error(`${entry.coordinate || "Migration entry"} is a mod and cannot be published to Mcfpm`);
  return entry;
}
