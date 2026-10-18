import type { ApiRuntime } from "@flowcatalyst/api-runtime";
import type { App, Plugin } from "vue";
import { useColorMode } from "./composables/useColorMode.ts";
import { ADMIN_DATA_KEY, adminData, type AdminDataOptions } from "./admin/data.ts";
import { API_RUNTIME_KEY } from "./composables/useSession.ts";

export interface FlowcatalystUiOptions {
  /** The application's runtime (createApiRuntime): the session for the shell and usePermissions. */
  readonly api?: ApiRuntime;
  /**
   * The shared admin data pages' port (docs/contracts/admin.md section 13): the application code and the
   * operations of the application's generated client. Needed by `standardAdminPages`.
   */
  readonly admin?: AdminDataOptions;
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
    if (options.admin !== undefined) {
      app.provide(ADMIN_DATA_KEY, adminData(options.admin));
    }
    app.runWithContext(() => {
      useColorMode();
    });
  },
};
