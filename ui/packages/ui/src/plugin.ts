import type { ApiRuntime } from "@flowcatalyst/api-runtime";
import type { App, Plugin } from "vue";
import { useColorMode } from "./composables/useColorMode.ts";
import { API_RUNTIME_KEY } from "./composables/useSession.ts";

export interface FlowcatalystUiOptions {
  /** The application's runtime (createApiRuntime): the session for the shell and usePermissions. */
  readonly api?: ApiRuntime;
}

/**
 * The @flowcatalyst/ui Vue plugin. Install it after the router and `@nuxt/ui/vue-plugin`:
 *
 *   app.use(router).use(ui).use(flowcatalystUi, { api });
 *
 * Applies the stored colour mode for the whole application (Nuxt UI's own colour mode is off, see
 * useColorMode) and provides the runtime to useSession, usePermissions and FwAppShell.
 */
export const flowcatalystUi: Plugin<[FlowcatalystUiOptions?]> = {
  install(app: App, options: FlowcatalystUiOptions = {}): void {
    if (options.api !== undefined) {
      app.provide(API_RUNTIME_KEY, options.api);
    }
    app.runWithContext(() => {
      useColorMode();
    });
  },
};
