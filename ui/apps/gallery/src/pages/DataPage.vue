<script setup lang="ts">
// Nuxt UI's data components used directly. The table is UTable with TanStack's features as Nuxt UI
// documents them (sorting from header buttons, the global filter, column visibility, row selection,
// pagination with UPagination): a list that loads its rows at once needs no wrapper. Server-paged
// lists use FwListPage (Orders).
import { FwStatusBadge, type SortingState, type StatusColor } from "@flowcatalyst/ui";
import type { TableColumn } from "@nuxt/ui";
import type { DropdownMenuItem } from "@nuxt/ui/components/DropdownMenu.vue";
import UAccordion from "@nuxt/ui/components/Accordion.vue";
import UAvatar from "@nuxt/ui/components/Avatar.vue";
import UBreadcrumb from "@nuxt/ui/components/Breadcrumb.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UCard from "@nuxt/ui/components/Card.vue";
import UCheckbox from "@nuxt/ui/components/Checkbox.vue";
import UDropdownMenu from "@nuxt/ui/components/DropdownMenu.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import UPagination from "@nuxt/ui/components/Pagination.vue";
import USeparator from "@nuxt/ui/components/Separator.vue";
import UTable from "@nuxt/ui/components/Table.vue";
import UTabs from "@nuxt/ui/components/Tabs.vue";
import { getPaginationRowModel, type Column, type PaginationState, type RowSelectionState, type VisibilityState } from "@tanstack/vue-table";
import { computed, h, shallowRef, useTemplateRef, watch } from "vue";
import GallerySection from "../components/GallerySection.vue";

interface Order {
  readonly id: string;
  readonly customer: string;
  readonly status: "OPEN" | "PLACED" | "CANCELLED";
  readonly total: number;
}

const customers = ["Acme", "Globex", "Initech", "Umbrella", "Hooli", "Stark", "Wayne", "Tyrell", "Soylent", "Cyberdyne", "Wonka", "Gringotts"];
const statuses = ["OPEN", "PLACED", "CANCELLED"] as const;
const orders: Order[] = customers.map((customer, index) => ({
  id: `ord_${String(1040 + index)}`,
  customer,
  status: statuses[index % 3] ?? "OPEN",
  total: Math.round(((index * 37) % 100) * 12.5) / 4 + 9.5,
}));

const colors: Readonly<Record<Order["status"], StatusColor>> = { OPEN: "info", PLACED: "success", CANCELLED: "neutral" };

/**
 * A header button that sorts the column (Nuxt UI's documented pattern). UTable 4.11.3 writes aria-sort
 * on the header cell of a column with `enableSorting: true`.
 */
function sortable(label: string) {
  return ({ column }: { column: Column<Order> }) => {
    const sorted = column.getIsSorted();
    return h(UButton, {
      label,
      color: "neutral",
      variant: "ghost",
      class: "-mx-2.5",
      icon: sorted === "asc" ? "i-lucide-arrow-up-narrow-wide" : sorted === "desc" ? "i-lucide-arrow-down-wide-narrow" : "i-lucide-arrow-up-down",
      onClick: () => {
        column.toggleSorting(sorted === "asc");
      },
    });
  };
}

const columns: TableColumn<Order>[] = [
  {
    id: "select",
    header: ({ table }) =>
      h(UCheckbox, {
        modelValue: table.getIsSomePageRowsSelected() ? "indeterminate" : table.getIsAllPageRowsSelected(),
        "onUpdate:modelValue": (value: unknown) => {
          table.toggleAllPageRowsSelected(value === true);
        },
        "aria-label": "Select all on this page",
      }),
    cell: ({ row }) =>
      h(UCheckbox, {
        modelValue: row.getIsSelected(),
        "onUpdate:modelValue": (value: unknown) => {
          row.toggleSelected(value === true);
        },
        "aria-label": `Select ${row.original.id}`,
      }),
    enableSorting: false,
    enableHiding: false,
  },
  { accessorKey: "id", header: sortable("Order"), enableSorting: true },
  { accessorKey: "customer", header: sortable("Customer"), enableSorting: true },
  { accessorKey: "status", header: "Status", cell: ({ row }) => h(FwStatusBadge, { status: row.original.status, colors }) },
  {
    accessorKey: "total",
    header: sortable("Total"),
    enableSorting: true,
    cell: ({ row }) => row.original.total.toFixed(2),
    meta: { class: { td: "text-right tabular-nums", th: "text-right" } },
  },
];

// UTable's models cannot all be typed with v-model under exactOptionalPropertyTypes (the props exclude
// undefined, the events include it; docs/ui/u0.md): each is a prop and an event.
const sorting = shallowRef<SortingState>([{ id: "customer", desc: false }]);
const filter = shallowRef("");
const visibility = shallowRef<VisibilityState>({});
const selection = shallowRef<RowSelectionState>({});
const pagination = shallowRef<PaginationState>({ pageIndex: 0, pageSize: 5 });
const paginationOptions = { getPaginationRowModel: getPaginationRowModel() };

/** What this page reads from UTable's exposed TanStack table. */
interface TableApi {
  readonly tableApi?: { getFilteredRowModel(): { readonly rows: readonly unknown[] } };
}
const table = useTemplateRef<TableApi>("table");
// The rows the filter leaves, read from the exposed table after it re-rendered with the new filter.
const filteredCount = shallowRef(orders.length);
watch(
  filter,
  () => {
    filteredCount.value = table.value?.tableApi?.getFilteredRowModel().rows.length ?? orders.length;
  },
  { flush: "post" },
);
const selectedCount = computed(() => Object.values(selection.value).filter(Boolean).length);

const hideable = [
  { id: "customer", label: "Customer" },
  { id: "status", label: "Status" },
  { id: "total", label: "Total" },
] as const;
const columnItems = computed<DropdownMenuItem[]>(() =>
  hideable.map((column) => ({
    label: column.label,
    type: "checkbox" as const,
    checked: visibility.value[column.id] !== false,
    onUpdateChecked: (checked: boolean) => {
      visibility.value = { ...visibility.value, [column.id]: checked };
    },
    onSelect: (event: Event) => {
      // Keep the menu open while columns are toggled.
      event.preventDefault();
    },
  })),
);

function setPage(page: number): void {
  pagination.value = { ...pagination.value, pageIndex: page - 1 };
}

function setFilter(value: string): void {
  filter.value = value;
  pagination.value = { ...pagination.value, pageIndex: 0 };
}

const tabs = [
  { label: "Lines", slot: "lines" as const },
  { label: "History", slot: "history" as const },
  { label: "Disabled", slot: "lines" as const, disabled: true },
  { label: "Notes", slot: "notes" as const },
];
const accordion = [
  { label: "Shipping", content: "Ships from the Rotterdam warehouse." },
  { label: "Billing", content: "Invoiced monthly." },
];
const crumbs = [{ label: "Orders", to: "/orders" }, { label: "ord_1042" }];
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="Data display" description="Nuxt UI's table, pagination, tabs, accordion, card, avatar, breadcrumb and separator, used directly" />

    <GallerySection
      :behaviours="['table-sort', 'table-filter-columns', 'table-select-rows', 'table-paginate']"
      title="Table (UTable)"
      description="TanStack's features through UTable: sort from the headers, filter, pick columns, select rows, page the loaded rows. Status cells are FwStatusBadge."
    >
      <div class="flex flex-wrap items-center justify-between gap-2">
        <UInput :model-value="filter" type="search" icon="i-lucide-search" placeholder="Filter orders…" aria-label="Filter orders" class="w-64 max-w-full" data-testid="table-filter" @update:model-value="setFilter" />
        <div class="flex items-center gap-3">
          <span class="text-sm text-muted" data-testid="table-selected">{{ selectedCount }} of {{ orders.length }} selected</span>
          <UDropdownMenu :items="columnItems" :modal="false" :content="{ align: 'end' }">
            <UButton label="Columns" icon="i-lucide-columns-3" color="neutral" variant="outline" data-testid="table-columns" />
          </UDropdownMenu>
        </div>
      </div>
      <!-- Scrolls sideways on a phone: a focusable, named region (FwListPage does the same). -->
      <div class="overflow-x-auto" tabindex="0" role="region" aria-label="Orders table">
        <UTable
          ref="table"
          :data="orders"
          :columns="columns"
          caption="Orders"
          :sorting="sorting"
          :global-filter="filter"
          :column-visibility="visibility"
          :row-selection="selection"
          :pagination="pagination"
          :pagination-options="paginationOptions"
          :get-row-id="(row: Order) => row.id"
          :ui="{ root: 'overflow-visible' }"
          data-testid="orders-table"
          @update:sorting="(value) => (sorting = value ?? [])"
          @update:column-visibility="(value) => (visibility = value ?? {})"
          @update:row-selection="(value) => (selection = value ?? {})"
          @update:pagination="(value) => (pagination = value ?? pagination)"
        />
      </div>
      <div class="flex justify-end">
        <UPagination :page="pagination.pageIndex + 1" :items-per-page="pagination.pageSize" :total="filteredCount" data-testid="table-pagination" @update:page="setPage" />
      </div>
    </GallerySection>

    <GallerySection title="Pagination (UPagination)">
      <UPagination :default-page="1" :total="40" :items-per-page="10" show-edges />
    </GallerySection>

    <GallerySection :behaviours="['tabs-keyboard']" title="Tabs (UTabs)" description="Themed to the kit's tabs (fcComponentTheme): a bordered list, the current tab on a neutral tint. Arrow keys move.">
      <UTabs :items="tabs" data-testid="tabs">
        <template #lines><p class="p-2 text-sm">Three lines.</p></template>
        <template #history><p class="p-2 text-sm">Placed on 2026-10-01.</p></template>
        <template #notes><p class="p-2 text-sm">No notes.</p></template>
      </UTabs>
    </GallerySection>

    <GallerySection title="Accordion (UAccordion)">
      <UAccordion :items="accordion" />
    </GallerySection>

    <GallerySection title="Card and avatar (UCard, UAvatar)">
      <UCard class="max-w-md">
        <template #header><h3 class="font-semibold">Ada Lovelace</h3></template>
        <div class="flex items-center gap-3">
          <UAvatar alt="Ada Lovelace" />
          <UAvatar src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 8 8'%3E%3Crect width='8' height='8' fill='%23486581'/%3E%3C/svg%3E" alt="Navy square" />
          <p class="text-sm text-muted">Initials, or an image from <code>img-src 'self' data:</code>.</p>
        </div>
      </UCard>
    </GallerySection>

    <GallerySection title="Breadcrumb and separator (UBreadcrumb, USeparator)">
      <UBreadcrumb :items="crumbs" />
      <USeparator />
      <USeparator label="or" />
    </GallerySection>
  </div>
</template>
