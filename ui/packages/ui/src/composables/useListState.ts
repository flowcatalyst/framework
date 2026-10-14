// URL-backed list state (docs/go/ui.md section 1.1: the platform SPA's useListState, ported to strict
// TypeScript). Filters, the page and the sort live in the query string, so a list is linkable and
// survives a reload; changes REPLACE the history entry (no Back-button spam). A filter change resets
// the page. Query parameters the list does not own are kept (a drawer child route's, a tab's).
//
//   const list = useListState({ pageSize: 50, sort: { field: "createdAt", order: "desc" } }, load);
//   const q = list.filter(stringFilter("q", { debounce: 300 }));   // Ref<string>
//   const status = list.filter(arrayFilter("status"));              // Ref<string[]>, ?status=A&status=B
//   const active = list.filter(booleanFilter("active"));            // Ref<boolean | null>
//
// Each filter is registered with a typed codec, so its ref is typed without assertions (the
// platform's version built the refs from a descriptor object with casts). The refs are shallow:
// assign a new array to an array filter (v-model does), do not push into it.
import { computed, shallowReactive, shallowRef, watch, type ComputedRef, type Ref, type ShallowRef, type WritableComputedRef } from "vue";
import { useRoute, useRouter, type LocationQuery, type LocationQueryValue } from "vue-router";

/** A raw query value as vue-router gives it. */
export type QueryValue = LocationQueryValue | LocationQueryValue[] | undefined;

/** How one filter reads and writes its query parameter. */
export interface FilterCodec<T> {
  /** The query parameter name. */
  readonly key: string;
  /** The value when the parameter is absent (a fresh value per call: arrays are not shared). */
  initial(): T;
  /** The value of the parameter, or undefined when absent or unreadable (then `initial()`). */
  parse(raw: QueryValue): T | undefined;
  /** The parameter for a value, or undefined for the initial value (left out of the URL). */
  serialize(value: T): string | string[] | undefined;
  /** Milliseconds to wait after a change before syncing and reloading (a search box). */
  readonly debounce?: number;
}

function first(raw: QueryValue): string | undefined {
  const value = Array.isArray(raw) ? raw[0] : raw;
  return typeof value === "string" && value !== "" ? value : undefined;
}

/** A text filter; `""` is "no filter". */
export function stringFilter(key: string, options: { readonly initial?: string; readonly debounce?: number } = {}): FilterCodec<string> {
  const initial = options.initial ?? "";
  return {
    key,
    initial: () => initial,
    parse: first,
    serialize: (value) => (value === initial ? undefined : value),
    ...(options.debounce === undefined ? {} : { debounce: options.debounce }),
  };
}

/** One of a fixed set of values (an enum filter); anything else in the URL is ignored. */
export function choiceFilter<const V extends string>(key: string, values: readonly V[]): FilterCodec<V | null> {
  return {
    key,
    initial: () => null,
    parse: (raw) => {
      const value = first(raw);
      return values.find((candidate) => candidate === value);
    },
    serialize: (value) => value ?? undefined,
  };
}

/** A repeated parameter (`?status=A&status=B`, the OpenAPI contract's list query form). */
export function arrayFilter(key: string): FilterCodec<string[]> {
  return {
    key,
    initial: () => [],
    parse: (raw) => {
      const values = (Array.isArray(raw) ? raw : [raw]).filter((v): v is string => typeof v === "string" && v !== "");
      return values.length === 0 ? undefined : values;
    },
    serialize: (value) => (value.length === 0 ? undefined : [...value]),
  };
}

/** A tri-state flag: null is "either". */
export function booleanFilter(key: string): FilterCodec<boolean | null> {
  return {
    key,
    initial: () => null,
    parse: (raw) => {
      const value = first(raw);
      return value === "true" ? true : value === "false" ? false : undefined;
    },
    serialize: (value) => (value === null ? undefined : String(value)),
  };
}

/** A number; null is "no filter". */
export function numberFilter(key: string): FilterCodec<number | null> {
  return {
    key,
    initial: () => null,
    parse: (raw) => {
      const value = first(raw);
      const parsed = value === undefined ? Number.NaN : Number(value);
      return Number.isFinite(parsed) ? parsed : undefined;
    },
    serialize: (value) => (value === null ? undefined : String(value)),
  };
}

export type SortOrder = "asc" | "desc";

/** The sort in TanStack's shape, for UTable's `sorting` prop. */
export type SortingState = { id: string; desc: boolean }[];

export interface ListStateOptions {
  /** Rows per page when the URL has none (offset lists; a cursor list ignores paging). */
  readonly pageSize?: number;
  /** The sort when the URL has none. */
  readonly sort?: { readonly field: string; readonly order: SortOrder };
}

export interface ListState {
  /** Registers a filter and returns its ref, initialised from the URL. */
  filter<T>(codec: FilterCodec<T>): Ref<T>;
  /** The current page, 1-based (`?page=`, absent on page 1). */
  readonly page: Ref<number>;
  /** Rows per page (`?size=`, absent at the default). */
  readonly pageSize: Ref<number>;
  /** Sort field (`?sort=`) and order (`?order=asc|desc`), absent at the defaults. */
  readonly sortField: Ref<string>;
  readonly sortOrder: Ref<SortOrder>;
  /** The sort as UTable takes and emits it (setting it resets the page). */
  readonly sorting: WritableComputedRef<SortingState>;
  /** How many filters differ from their initial value (the table toolbar's badge). */
  readonly activeFilterCount: ComputedRef<number>;
  /** True when any filter is active. */
  readonly hasActiveFilters: ComputedRef<boolean>;
  /** Resets every filter and the page at once: one URL update, one reload. */
  clearFilters(): void;
  /** Runs `fn` without URL updates or reloads, then syncs and reloads once. */
  batch(fn: () => void): void;
}

interface Registered {
  readonly key: string;
  readonly active: () => boolean;
  readonly reset: () => void;
  readonly write: (query: Record<string, string | string[]>) => void;
}

function readPositive(raw: QueryValue): number | undefined {
  const value = first(raw);
  const parsed = value === undefined ? Number.NaN : Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : undefined;
}

function normalise(query: LocationQuery | Record<string, string | string[]>): string {
  const entries = Object.entries(query)
    .map(([key, value]) => [key, (Array.isArray(value) ? value : [value]).filter((v) => typeof v === "string")] as const)
    .filter(([, values]) => values.length > 0)
    .sort(([a], [b]) => a.localeCompare(b));
  return JSON.stringify(entries);
}

/**
 * URL-backed list state. `onChange` runs after every change that should reload the list (a filter,
 * after its debounce; the page; the size; the sort), never for the initial values.
 */
export function useListState(options: ListStateOptions = {}, onChange?: () => void): ListState {
  const route = useRoute();
  const router = useRouter();
  const defaultSize = options.pageSize ?? 50;
  const defaultField = options.sort?.field ?? "";
  const defaultOrder = options.sort?.order ?? "desc";
  const registered = shallowReactive<Registered[]>([]);
  let quiet = false;
  let timer: ReturnType<typeof setTimeout> | undefined;

  const page = shallowRef(readPositive(route.query["page"]) ?? 1);
  const pageSize = shallowRef(readPositive(route.query["size"]) ?? defaultSize);
  const sortField = shallowRef(first(route.query["sort"]) ?? defaultField);
  const order = first(route.query["order"]);
  const sortOrder = shallowRef<SortOrder>(order === "asc" || order === "desc" ? order : defaultOrder);

  function sync(): void {
    const owned = new Set(["page", "size", "sort", "order", ...registered.map((r) => r.key)]);
    const query: Record<string, string | string[]> = {};
    for (const [key, value] of Object.entries(route.query)) {
      if (owned.has(key)) {
        continue;
      }
      if (typeof value === "string") {
        query[key] = value;
      } else if (Array.isArray(value)) {
        query[key] = value.filter((v): v is string => typeof v === "string");
      }
    }
    for (const filter of registered) {
      filter.write(query);
    }
    if (page.value > 1) {
      query["page"] = String(page.value);
    }
    if (pageSize.value !== defaultSize) {
      query["size"] = String(pageSize.value);
    }
    if (sortField.value !== defaultField) {
      query["sort"] = sortField.value;
    }
    if (sortOrder.value !== defaultOrder) {
      query["order"] = sortOrder.value;
    }
    if (normalise(query) !== normalise(route.query)) {
      void router.replace({ query, hash: route.hash });
    }
  }

  function changed(): void {
    clearTimeout(timer);
    sync();
    onChange?.();
  }

  function filter<T>(codec: FilterCodec<T>): Ref<T> {
    const initial: T = codec.parse(route.query[codec.key]) ?? codec.initial();
    const state: ShallowRef<T> = shallowRef(initial);
    registered.push({
      key: codec.key,
      active: () => codec.serialize(state.value) !== undefined,
      reset: () => {
        state.value = codec.initial();
      },
      write: (query) => {
        const value = codec.serialize(state.value);
        if (value !== undefined) {
          query[codec.key] = value;
        }
      },
    });
    watch(
      () => JSON.stringify(codec.serialize(state.value) ?? null),
      () => {
        if (quiet) {
          return;
        }
        quiet = true;
        page.value = 1;
        quiet = false;
        if (codec.debounce !== undefined && codec.debounce > 0) {
          clearTimeout(timer);
          timer = setTimeout(changed, codec.debounce);
        } else {
          changed();
        }
      },
      { flush: "sync" },
    );
    return state;
  }

  watch(
    [page, pageSize, sortField, sortOrder],
    () => {
      if (!quiet) {
        changed();
      }
    },
    { flush: "sync" },
  );

  function batch(fn: () => void): void {
    quiet = true;
    try {
      fn();
    } finally {
      quiet = false;
    }
    changed();
  }

  const sorting = computed<SortingState>({
    get: () => (sortField.value === "" ? [] : [{ id: sortField.value, desc: sortOrder.value === "desc" }]),
    set: (next) => {
      const head = next[0];
      batch(() => {
        sortField.value = head?.id ?? defaultField;
        sortOrder.value = head === undefined ? defaultOrder : head.desc ? "desc" : "asc";
        page.value = 1;
      });
    },
  });

  const activeFilterCount = computed(() => registered.filter((r) => r.active()).length);

  return {
    filter,
    page,
    pageSize,
    sortField,
    sortOrder,
    sorting,
    activeFilterCount,
    hasActiveFilters: computed(() => activeFilterCount.value > 0),
    clearFilters: () => {
      batch(() => {
        for (const r of registered) {
          r.reset();
        }
        page.value = 1;
      });
    },
    batch,
  };
}
