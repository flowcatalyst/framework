<script setup lang="ts" generic="T extends object">
// A list page (ui-kit data-table.md; the SPA's list pages): Nuxt UI's UPageHeader, the table toolbar,
// UTable, UEmpty when there are no rows (with Clear filters while filtered, in a status region so a
// filter that empties the list is read out), a pager slot and a drawer outlet. Data in through props,
// intent out through events: the page owns the calls (useListState for the URL, useCursorPagination or
// offset paging) and renders its drawer route in the `drawer` slot (`<RouterView />`).
// `searchLoadedRows` hands the search box to UTable's global filter (TanStack): for operations without
// a search parameter, it narrows the rows already loaded.
//
// UTable's `v-model:sorting` cannot be typed under exactOptionalPropertyTypes (docs/ui/u0.md), so the
// sorting is split into the prop and an event here, once.
import type { TableColumn, TableRow } from "@nuxt/ui";
import UEmpty from "@nuxt/ui/components/Empty.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import UTable from "@nuxt/ui/components/Table.vue";
import { computed } from "vue";
import FwTableToolbar from "../components/FwTableToolbar.vue";
import type { SortingState } from "../composables/useListState.ts";

const {
  title,
  subtitle,
  data,
  columns,
  loading = false,
  sorting = [],
  activeFilterCount = 0,
  emptyMessage = "Nothing here yet.",
  rowKey,
  selectedKey,
  searchPlaceholder = "Search…",
  searchLoadedRows = false,
} = defineProps<{
  title: string;
  subtitle?: string;
  data: readonly T[];
  columns: TableColumn<T>[];
  loading?: boolean;
  sorting?: SortingState;
  activeFilterCount?: number;
  emptyMessage?: string;
  /** A stable key per row (the record id): row identity and the selected-row highlight. */
  rowKey: (row: T) => string;
  /** The key of the record the drawer shows (highlighted). */
  selectedKey?: string | undefined;
  searchPlaceholder?: string;
  /** The search narrows the loaded rows (UTable's global filter) instead of going to the server. */
  searchLoadedRows?: boolean;
}>();

const search = defineModel<string>("search", { default: "" });

const emit = defineEmits<{
  "update:sorting": [sorting: SortingState];
  /** A row was chosen (click, Enter or Space on the row). */
  select: [row: T];
  refresh: [];
  clear: [];
}>();

const slots = defineSlots<{
  /** Buttons beside the title (New order). */
  actions?: () => unknown;
  /** The filter fields (the toolbar's Filters popover). */
  filters?: () => unknown;
  /** Under the table: FwCursorPager or FwPaginator. */
  pager?: () => unknown;
  /** The drawer route's outlet (`<RouterView />`). */
  drawer?: () => unknown;
}>();

const rows = computed(() => [...data]);
const filtered = computed(() => search.value !== "" || activeFilterCount > 0);
const emptyActions = computed(() =>
  filtered.value
    ? [{ label: "Clear filters", color: "primary" as const, variant: "link" as const, onClick: () => { emit("clear"); } }]
    : [],
);
const meta = computed(() => ({
  class: {
    tr: (row: TableRow<T>) => (selectedKey !== undefined && rowKey(row.original) === selectedKey ? "bg-elevated [&>td]:text-highlighted" : ""),
  },
}));

function onSelect(_event: Event, row: TableRow<T>): void {
  emit("select", row.original);
}

function onSorting(next: SortingState | undefined): void {
  emit("update:sorting", next ?? []);
}
</script>

<template>
  <div class="flex flex-col" data-testid="fw-list-page">
    <UPageHeader :title="title" v-bind="subtitle === undefined ? {} : { description: subtitle }">
      <template v-if="slots.actions !== undefined" #links><slot name="actions" /></template>
    </UPageHeader>
    <div class="overflow-hidden rounded-[2px] border border-default bg-default">
      <FwTableToolbar
        v-model:search="search"
        :active-filter-count="activeFilterCount"
        :refreshing="loading"
        :search-placeholder="searchPlaceholder"
        @refresh="emit('refresh')"
        @clear="emit('clear')"
      >
        <template v-if="slots.filters !== undefined" #filters><slot name="filters" /></template>
      </FwTableToolbar>
      <!-- The table scrolls sideways in a narrow window: the scrolling element is a named, focusable
           region, so keyboard users can scroll it (axe scrollable-region-focusable). -->
      <div class="overflow-x-auto" tabindex="0" role="region" :aria-label="title" data-testid="fw-list-table">
      <UTable
        :data="rows"
        :columns="columns"
        :loading="loading"
        :sorting="sorting"
        :meta="meta"
        :caption="title"
        :get-row-id="(row: T) => rowKey(row)"
        v-bind="searchLoadedRows ? { globalFilter: search } : {}"
        class="w-full"
        :ui="{ root: 'overflow-visible' }"
        @update:sorting="onSorting"
        @select="onSelect"
      >
        <template #empty>
          <div role="status" data-testid="fw-list-empty">
            <UEmpty :description="emptyMessage" icon="i-lucide-inbox" :actions="emptyActions" variant="naked" />
          </div>
        </template>
      </UTable>
      </div>
      <slot name="pager" />
    </div>
    <slot name="drawer" />
  </div>
</template>
