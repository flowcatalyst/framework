// The behaviour checklist (src/behaviours.ts), replacing U1's catalog of ui-kit specs (owner ruling
// 2026-10-14: the specs are a checklist of behaviours, not a visual or API contract). Here: each page
// shows a marked demonstration of every row assigned to it. That every row is exercised by a passing
// test is enforced by e2e/behaviour-reporter.ts at the end of a full run.
import { behaviours } from "../src/behaviours.ts";
import { pages } from "../src/pages.ts";
import { covers, expect, openPage, test } from "./fixtures.ts";

test("every checklist row names a gallery page (or every page) and has a unique id", () => {
  const paths = new Set(pages.map((page) => page.path));
  for (const row of behaviours) {
    expect(row.page === "*" || paths.has(row.page), `${row.id}: ${row.page}`).toBe(true);
  }
  expect(new Set(behaviours.map((row) => row.id)).size).toBe(behaviours.length);
});

for (const { path, heading } of pages) {
  const expected = behaviours.filter((row) => row.page === path).map((row) => row.id);
  if (expected.length === 0) {
    continue;
  }
  test(`${path} demonstrates: ${expected.join(", ")}`, covers("one-h1", "strict-csp"), async ({ page, csp }) => {
    await openPage(page, path, heading);
    await expect(page.getByRole("heading", { level: 1 })).toHaveCount(1);
    for (const id of expected) {
      await expect(page.locator(`[data-behaviour~="${id}"]`).first(), id).toBeAttached();
    }
    await csp.expectClean();
  });
}
