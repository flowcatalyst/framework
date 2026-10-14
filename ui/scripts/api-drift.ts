// Drift check for generated clients (docs/contracts/openapi.md section 11): run after the generator,
// it fails when the given folders differ from the commit, new and deleted files included (plain
// `git diff --exit-code` does not see a file the generator added).
//
//   openapi-ts && node ../../scripts/api-drift.ts src/generated test/orders-demo/api
import { execFileSync } from "node:child_process";

const paths = process.argv.slice(2);
if (paths.length === 0) {
  console.error("usage: node scripts/api-drift.ts <generated folder>...");
  process.exit(2);
}

const status = execFileSync("git", ["status", "--porcelain", "--untracked-files=all", "--", ...paths], {
  encoding: "utf8",
});
if (status.trim() !== "") {
  console.error(`Generated client out of date (regenerate and commit):\n${status}`);
  console.error(execFileSync("git", ["diff", "--stat", "--", ...paths], { encoding: "utf8" }));
  process.exit(1);
}
console.log(`no drift: ${paths.join(", ")}`);
