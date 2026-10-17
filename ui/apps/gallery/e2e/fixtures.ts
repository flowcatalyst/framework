// Every test runs with the kit's CSP monitor (@flowcatalyst/ui/e2e, docs/guide/e2e-kit.md): it records
// each `securitypolicyviolation` event (installed before any page script runs), each console message
// about the Content Security Policy, each request to another origin (the Iconify API, a font host, ...)
// and each uncaught page error. The tests assert all four are empty. The gallery has no backend, so no
// request may leave its origin.
import { createTest, expect, openPage } from "@flowcatalyst/ui/e2e";
import type { TestDetails } from "@playwright/test";
import type { BehaviourId } from "../src/behaviours.ts";

export const test = createTest();

export { expect, openPage };

/**
 * The checklist rows (src/behaviours.ts) a test exercises, as its details:
 * `test("...", covers("drawer-focus", "drawer-discard"), async ({ page }) => { ... })`. The behaviour
 * reporter (behaviour-reporter.ts) fails a full run in which a row is claimed by no passing test.
 */
export function covers(...ids: readonly BehaviourId[]): TestDetails {
  return { annotation: ids.map((id) => ({ type: "behaviour", description: id })) };
}
