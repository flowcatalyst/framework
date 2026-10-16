// The admin shell (M4d, FwAdminShell) in the gallery, against the in-page fake server: navigation from
// the server's sections and the session's permissions, the route guard, breadcrumbs, the failed-load
// state. `?as=admin|users|none|down` chooses the viewer (src/fake-server.ts). Strict axe in both
// themes at desktop and 390 px; the strict CSP stays clean.
import type { Page } from "@playwright/test";
import { axeViolations } from "./axe-tools.ts";
import { covers, expect, openPage, test } from "./fixtures.ts";

const sidebar = (page: Page) => page.getByRole("navigation", { name: "Main" });

test("an administrator sees the sections the server lists and the application has pages for, and a way back", covers("admin-nav-permissions", "admin-unlisted-section"), async ({ page, csp }) => {
  const warnings: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "warning") {
      warnings.push(message.text());
    }
  });
  await openPage(page, "/admin/about?as=admin", "About");
  // The server also lists "events"; the application has no page for it, so it is not shown.
  await expect(sidebar(page).getByRole("link")).toHaveText(["About", "Users", "Roles", "Back to the gallery"]);
  expect(warnings.some((text) => text.includes("'events'"))).toBe(true);
  await expect(sidebar(page).getByRole("link", { name: "About" })).toHaveAttribute("aria-current", "page");
  await sidebar(page).getByRole("link", { name: "Back to the gallery" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Admin shell" })).toBeVisible();
  await csp.expectClean();
});

test("a users administrator sees only what the server lists for them; unlisted sections are absent", covers("admin-nav-permissions"), async ({ page, csp }) => {
  await openPage(page, "/admin/about?as=users", "About");
  await expect(sidebar(page).getByRole("link")).toHaveText(["About", "Users", "Back to the gallery"]);
  await expect(sidebar(page).getByRole("link", { name: "Roles" })).toHaveCount(0);
  await csp.expectClean();
});

test("a route needs its permission: the not-permitted page opens without it", covers("admin-route-guard"), async ({ page, csp }) => {
  await page.goto("/admin/roles?as=users");
  await expect(page.getByRole("heading", { level: 1, name: "Not permitted" })).toBeVisible();
  await expect(page).toHaveURL(/\/not-permitted$/);

  await page.goto("/admin/about?as=none");
  await expect(page.getByRole("heading", { level: 1, name: "Not permitted" })).toBeVisible();
  // The landing route /admin leads to About, which is guarded the same way.
  await page.goto("/admin");
  await expect(page.getByRole("heading", { level: 1, name: "Not permitted" })).toBeVisible();
  await csp.expectClean();
});

test("breadcrumbs: Admin is a link, the current page is text marked aria-current", covers("admin-breadcrumbs"), async ({ page, csp }) => {
  await openPage(page, "/admin/users?as=admin", "Users");
  const crumbs = page.getByTestId("fw-admin-breadcrumbs");
  await expect(crumbs.getByRole("link", { name: "Admin" })).toHaveAttribute("href", "/admin");
  await expect(crumbs.locator('[aria-current="page"]')).toHaveText("Users");
  await expect(crumbs.getByRole("link")).toHaveCount(1);
  await csp.expectClean();
});

test("a navigation that cannot be loaded is an inline error; Try again loads it", covers("admin-nav-failed"), async ({ page, csp }) => {
  await openPage(page, "/admin/about?as=down", "About");
  const failed = page.getByTestId("fw-admin-nav-failed");
  await expect(failed).toContainText("could not be loaded");
  await expect(sidebar(page).getByRole("link")).toHaveText(["Back to the gallery"]);
  expect(await axeViolations(page), "failed navigation").toEqual([]);
  await page.getByTestId("fw-admin-nav-retry").click();
  await expect(failed).toHaveCount(0);
  await expect(sidebar(page).getByRole("link")).toHaveText(["About", "Users", "Roles", "Back to the gallery"]);
  await csp.expectClean();
});

for (const scheme of ["light", "dark"] as const) {
  test(`axe: the admin pages in ${scheme} at desktop and 390 px`, async ({ page, csp }) => {
    await page.emulateMedia({ colorScheme: scheme });
    for (const size of [{ width: 1280, height: 800 }, { width: 390, height: 800 }]) {
      await page.setViewportSize(size);
      for (const [path, heading] of [
        ["/admin/about?as=admin", "About"],
        ["/admin/users", "Users"],
        ["/not-permitted?as=none", "Not permitted"],
        ["/admin-shell", "Admin shell"],
      ] as const) {
        await openPage(page, path, heading);
        expect(await axeViolations(page), `${path} ${scheme} ${String(size.width)}`).toEqual([]);
      }
    }
    await csp.expectClean();
  });
}
