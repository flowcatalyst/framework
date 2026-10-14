// docs/go/ui.md section 17, U1 exit: every row of section 10 has its gallery entry. Each ui-kit spec
// has a catalog row (src/catalog.ts), and each row's page shows an element marked with its spec.
import { readdirSync } from "node:fs";
import { join } from "node:path";
import { catalog } from "../src/catalog.ts";
import { pages } from "../src/pages.ts";
import { expect, openPage, test } from "./fixtures.ts";

const specsDir = join(import.meta.dirname, "../../../../ui-kit/components");
const specs = readdirSync(specsDir)
  .filter((file) => file.endsWith(".md") && file !== "README.md" && !file.startsWith("_"))
  .map((file) => file.slice(0, -3))
  .sort();

test("every ui-kit spec has a catalog row, and the counts are section 10's", () => {
  expect(catalog.map((entry) => entry.spec).sort()).toEqual(specs);
  const count = (status: string): number => catalog.filter((entry) => entry.status === status).length;
  expect([count("covered"), count("composed"), count("built"), count("not ported")]).toEqual([37, 11, 11, 1]);
});

for (const { path, heading } of pages) {
  const expected = catalog.filter((entry) => entry.page === path).map((entry) => entry.spec);
  if (expected.length === 0) {
    continue;
  }
  test(`${path} shows its entries: ${expected.join(", ")}`, async ({ page, csp }) => {
    await openPage(page, path, heading);
    for (const spec of expected) {
      await expect(page.locator(`[data-spec~="${spec}"]`).first(), spec).toBeAttached();
    }
    await csp.expectClean();
  });
}
