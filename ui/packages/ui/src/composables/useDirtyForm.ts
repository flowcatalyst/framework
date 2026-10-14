// "Has the form changed?" by structural comparison with a snapshot (the platform SPA's useDirtyForm;
// docs/ui/u0.md: Nuxt UI's UForm `dirty` means "had input", so typing a value back leaves it true).
// Typing a value back here clears it. Optionally guards leaving the route while dirty.
import { computed, shallowRef, type ComputedRef } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { confirmDiscardChanges, useConfirm } from "./useConfirm.ts";

export interface DirtyForm {
  /** True when the values differ from the last snapshot (false before the first). */
  readonly dirty: ComputedRef<boolean>;
  /** Takes the snapshot (after loading a record, after a successful save). */
  markClean(): void;
  /** Forgets the snapshot (the form is closed). */
  reset(): void;
}

export interface DirtyFormOptions {
  /** Ask "Discard changes?" before leaving the route while dirty (needs a router and UApp). */
  readonly guardRouteLeave?: boolean;
}

/** `snapshot` returns the form's values; compared as JSON (order-sensitive for arrays). */
export function useDirtyForm(snapshot: () => unknown, options: DirtyFormOptions = {}): DirtyForm {
  const baseline = shallowRef<string | undefined>(undefined);
  const dirty = computed(() => baseline.value !== undefined && JSON.stringify(snapshot()) !== baseline.value);

  if (options.guardRouteLeave === true) {
    const confirm = useConfirm();
    onBeforeRouteLeave(() => (dirty.value ? confirmDiscardChanges(confirm) : true));
  }

  return {
    dirty,
    markClean: () => {
      baseline.value = JSON.stringify(snapshot());
    },
    reset: () => {
      baseline.value = undefined;
    },
  };
}
