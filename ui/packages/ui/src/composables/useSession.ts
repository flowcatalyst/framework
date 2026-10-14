// The application's @flowcatalyst/api-runtime, provided by the plugin
// (`app.use(flowcatalystUi, { api })`), and what components read from it: the signed-in session and
// the permission check (show/hide only; the server checks every operation).
import type { ApiRuntime, AuthSessionAuthenticated, BrowserSession } from "@flowcatalyst/api-runtime";
import { computed, inject, type ComputedRef, type InjectionKey } from "vue";

export const API_RUNTIME_KEY: InjectionKey<ApiRuntime> = Symbol("flowcatalyst-api-runtime");

/** The runtime given to the plugin, or undefined (the gallery, a test without one). */
export function useApiRuntime(): ApiRuntime | undefined {
  return inject(API_RUNTIME_KEY, undefined);
}

/** The browser session, or undefined without a runtime. */
export function useSession(): BrowserSession | undefined {
  return useApiRuntime()?.session;
}

export interface Permissions {
  /** The signed-in user, or undefined. */
  readonly user: ComputedRef<AuthSessionAuthenticated | undefined>;
  /** True when the session holds a permission granting `required` (the platform's wildcard rule). */
  can(required: string): boolean;
}

export function usePermissions(): Permissions {
  const session = useSession();
  return {
    user: computed(() => session?.user.value),
    can: (required) => session?.can(required) ?? false,
  };
}
