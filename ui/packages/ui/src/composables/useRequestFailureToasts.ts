// The request-failure fallback (ui-kit flash-stack.md, "Fallback"): when a call is refused (403), fails
// on the server (5xx) or never reaches it, the user sees a message even if the page's own handling
// says nothing, so a failed click is never silent. At most one message per kind at a time. A 401 is
// not shown: the runtime is already navigating to sign-in. FwAppShell calls this for the provided
// runtime; an application without the shell calls it once in its root component.
import { REQUEST_FAILURE_TEXT, type ApiRuntime } from "@flowcatalyst/api-runtime";
import { onScopeDispose } from "vue";
import { useFlash } from "./useFlash.ts";

export function useRequestFailureToasts(runtime: ApiRuntime | undefined): void {
  if (runtime === undefined) {
    return;
  }
  const flash = useFlash();
  const stop = runtime.onFailure((failure) => {
    const text = REQUEST_FAILURE_TEXT[failure.kind];
    const show = failure.kind === "refused" ? flash.warning : flash.error;
    show(text.summary, { detail: text.detail, id: `fw-request-${failure.kind}` });
  });
  onScopeDispose(stop);
}
