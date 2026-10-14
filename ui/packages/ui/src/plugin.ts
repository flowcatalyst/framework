import type { App, Plugin } from "vue";
import { useColorMode } from "./composables/useColorMode.ts";

/**
 * The @flowcatalyst/ui Vue plugin. Install it after the router and `@nuxt/ui/vue-plugin`:
 *
 *   app.use(router).use(ui).use(flowcatalystUi);
 *
 * U0 scope: applies the stored colour mode for the whole application (what Nuxt UI's own colour
 * mode plugin did before it was switched off). The `api` and `navigation` options of
 * docs/go/ui.md section 4 arrive with U2.
 */
export const flowcatalystUi: Plugin = {
  install(app: App): void {
    app.runWithContext(() => {
      useColorMode();
    });
  },
};
