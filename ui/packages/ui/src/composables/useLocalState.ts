// The one place that touches localStorage (docs/go/ui.md section 3.2; the platform's useLocalState):
// a ref backed by a key, read once and written on change. A stored value that fails `accept` (an old
// format, a hand edit) is ignored; storage that is full or blocked is ignored too.
import { shallowRef, watch, type Ref } from "vue";

const PREFIX = "fc:";

export function useLocalState<T>(key: string, initial: T, accept: (value: unknown) => value is T): Ref<T> {
  const storageKey = `${PREFIX}${key}`;
  let start = initial;
  try {
    const stored = localStorage.getItem(storageKey);
    if (stored !== null) {
      const parsed: unknown = JSON.parse(stored);
      if (accept(parsed)) {
        start = parsed;
      }
    }
  } catch {
    // Unreadable or blocked: the initial value.
  }
  const state = shallowRef<T>(start);
  watch(state, (next) => {
    try {
      if (next === undefined || next === null) {
        localStorage.removeItem(storageKey);
      } else {
        localStorage.setItem(storageKey, JSON.stringify(next));
      }
    } catch {
      // Full or blocked: the value lives for this page only.
    }
  });
  return state;
}
