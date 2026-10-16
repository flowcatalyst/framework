import { describe, expect, it } from "vitest";
import { DEDUPED_PACKAGES } from "../src/vite.ts";

// A linked package's imports resolve from its real location (outside the consumer's lockfile) unless the
// bundler is told to resolve them from the consumer's root. Anything holding module state must be one copy:
// Vue and the router (injection keys), Nuxt UI and Reka UI (contexts), VueUse, and Zod, whose `jitless`
// setting (strict CSP) is made by @flowcatalyst/api-runtime and must reach the copy the generated schemas use.
describe("flowcatalystUi() resolve.dedupe", () => {
  it("lists every package that holds module state", () => {
    expect([...DEDUPED_PACKAGES].sort()).toEqual(
      ["@nuxt/ui", "@vueuse/core", "reka-ui", "vue", "vue-router", "zod"].sort(),
    );
  });
});
