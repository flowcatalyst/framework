// @flowcatalyst/ui/e2e: the browser end-to-end kit (docs/guide/e2e-kit.md). The TypeScript half of the
// pair whose Java half is `E2eLauncher` in framework-auth's test-jar. Import it from Playwright
// configurations and specs only; it needs the peer dependencies @playwright/test and @axe-core/playwright.
export { axeViolations, WCAG_TAGS } from "./axe.ts";
export { defineE2eConfig, backendCommand, type E2eConfigOptions } from "./config.ts";
export {
  authorizeError,
  CONTROL_PORT_ENV,
  control,
  controlOrigin,
  endSessions,
  providerDown,
  signInAs,
  signInQuery,
  switchTo,
  type SignInAs,
} from "./control.ts";
export { createTest, expect, fetchJson, isForeignRequest, openPage, test, type CspMonitor, type CspOptions, type JsonAnswer } from "./fixtures.ts";
export { findRepositoryRoot, javaArguments, mavenArguments, startBackend, type BackendOptions } from "./start-backend.ts";
