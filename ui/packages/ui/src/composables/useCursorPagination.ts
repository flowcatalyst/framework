// Cursor pagination (the platform SPA's useCursorPagination in strict TypeScript) for operations that
// answer `{items, hasMore, nextCursor}` (the OpenAPI contract's CursorPage, ui.md section 6.2):
// "Newest" (the first page), "Older" (forward with `nextCursor`) and "Newer" (back, from an
// in-session stack of the cursors that produced each page; no reverse query on the server). After a
// reload the stack starts again at the first page, the usual cursor trade-off. A filter change calls
// `reset()`. A slower, older answer never overwrites a newer one.
import { computed, shallowRef, type ComputedRef, type ShallowRef } from "vue";

/**
 * The page shape the framework's cursor operations answer. `hasMore` may be left out when the
 * operation only sends `nextCursor` (the Java orders example's page): then a cursor means "more".
 */
export interface CursorPage<T> {
  readonly items: readonly T[];
  readonly hasMore?: boolean;
  readonly nextCursor?: string;
}

export interface CursorPaginationOptions<T> {
  /** Loads one page; `cursor` is undefined for the first. Reject to signal a failure. */
  readonly fetchPage: (cursor: string | undefined) => Promise<CursorPage<T>>;
  /** Called with a failed load's rejection (the items stay as they were). */
  readonly onError?: (error: unknown) => void;
}

export interface CursorPagination<T> {
  readonly items: Readonly<ShallowRef<readonly T[]>>;
  readonly loading: Readonly<ShallowRef<boolean>>;
  /** An older page exists (the server said `hasMore`). */
  readonly hasMore: Readonly<ShallowRef<boolean>>;
  /** A newer page exists in this session's stack. */
  readonly hasPrev: ComputedRef<boolean>;
  /** 1-based position in this session's stack. */
  readonly page: ComputedRef<number>;
  /** The first page; clears the stack. */
  loadFirst(): Promise<void>;
  /** The next (older) page. */
  loadNext(): Promise<void>;
  /** The previous (newer) page from the stack. */
  loadPrev(): Promise<void>;
  /** The current page again (after a change to a row). */
  refresh(): Promise<void>;
  /** Same as loadFirst, for filter-change call sites. */
  reset(): Promise<void>;
}

export function useCursorPagination<T>(options: CursorPaginationOptions<T>): CursorPagination<T> {
  const items = shallowRef<readonly T[]>([]);
  const loading = shallowRef(false);
  const hasMore = shallowRef(false);
  // The cursors that produced each rendered page; the top produced the page shown now.
  const stack = shallowRef<readonly (string | undefined)[]>([undefined]);
  let nextCursor: string | undefined;
  let latest = 0;

  async function fetchAt(cursor: string | undefined): Promise<boolean> {
    latest += 1;
    const ticket = latest;
    loading.value = true;
    try {
      const result = await options.fetchPage(cursor);
      if (ticket !== latest) {
        return false;
      }
      items.value = result.items;
      hasMore.value = result.hasMore ?? result.nextCursor !== undefined;
      nextCursor = result.nextCursor;
      return true;
    } catch (error) {
      if (ticket === latest) {
        options.onError?.(error);
      }
      return false;
    } finally {
      if (ticket === latest) {
        loading.value = false;
      }
    }
  }

  async function loadFirst(): Promise<void> {
    if (await fetchAt(undefined)) {
      stack.value = [undefined];
    }
  }

  async function loadNext(): Promise<void> {
    const cursor = nextCursor;
    if (!hasMore.value || cursor === undefined) {
      return;
    }
    if (await fetchAt(cursor)) {
      stack.value = [...stack.value, cursor];
    }
  }

  async function loadPrev(): Promise<void> {
    if (stack.value.length <= 1) {
      return;
    }
    const previous = stack.value.slice(0, -1);
    if (await fetchAt(previous[previous.length - 1])) {
      stack.value = previous;
    }
  }

  async function refresh(): Promise<void> {
    await fetchAt(stack.value[stack.value.length - 1]);
  }

  return {
    items,
    loading,
    hasMore,
    hasPrev: computed(() => stack.value.length > 1),
    page: computed(() => stack.value.length),
    loadFirst,
    loadNext,
    loadPrev,
    refresh,
    reset: loadFirst,
  };
}
