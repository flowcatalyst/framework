<script setup lang="ts">
import { FwPageHeader } from "@flowcatalyst/ui";
import UAccordion from "@nuxt/ui/components/Accordion.vue";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UAvatar from "@nuxt/ui/components/Avatar.vue";
import UBadge from "@nuxt/ui/components/Badge.vue";
import UCard from "@nuxt/ui/components/Card.vue";
import UEmpty from "@nuxt/ui/components/Empty.vue";
import UKbd from "@nuxt/ui/components/Kbd.vue";
import UPagination from "@nuxt/ui/components/Pagination.vue";
import UProgress from "@nuxt/ui/components/Progress.vue";
import USkeleton from "@nuxt/ui/components/Skeleton.vue";
import UTable from "@nuxt/ui/components/Table.vue";
import type { TableColumn } from "@nuxt/ui/components/Table.vue";
import UTabs from "@nuxt/ui/components/Tabs.vue";
import { h, ref } from "vue";

interface Order {
  readonly id: string;
  readonly customer: string;
  readonly status: "OPEN" | "PLACED" | "CANCELLED";
  readonly total: number;
}

const orders: Order[] = [
  { id: "ord_1040", customer: "Acme", status: "OPEN", total: 120.5 },
  { id: "ord_1041", customer: "Globex", status: "PLACED", total: 99 },
  { id: "ord_1042", customer: "Initech", status: "CANCELLED", total: 15.25 },
  { id: "ord_1043", customer: "Umbrella", status: "PLACED", total: 1200 },
];

const statusColor = { OPEN: "info", PLACED: "success", CANCELLED: "neutral" } as const;

const columns: TableColumn<Order>[] = [
  { accessorKey: "id", header: "Order" },
  { accessorKey: "customer", header: "Customer" },
  {
    accessorKey: "status",
    header: "Status",
    cell: ({ row }) => h(UBadge, { color: statusColor[row.original.status], variant: "subtle", label: row.original.status }),
  },
  { accessorKey: "total", header: "Total", cell: ({ row }) => row.original.total.toFixed(2) },
];

// UTable's `sorting` model is declared `sorting?: SortingState` but emits `SortingState | undefined`;
// under exactOptionalPropertyTypes no ref type satisfies both sides of `v-model:sorting`, so the
// binding is split (docs/ui/u0.md). FwListPage (U2) owns this once.
const sorting = ref<{ id: string; desc: boolean }[]>([{ id: "customer", desc: false }]);
const page = ref(1);
const tabs = [
  { label: "Lines", slot: "lines" as const },
  { label: "History", slot: "history" as const },
];
const accordion = [
  { label: "Shipping", content: "Ships from the Rotterdam warehouse." },
  { label: "Billing", content: "Invoiced monthly." },
];
</script>

<template>
  <div class="space-y-6">
    <FwPageHeader title="Data display" subtitle="Table, badges, tabs, accordion, alert and status components" />

    <UTable :sorting="sorting" :data="orders" :columns="columns" data-testid="orders-table" @update:sorting="(value) => (sorting = value ?? [])" />
    <UPagination v-model:page="page" :total="40" :items-per-page="10" />

    <UTabs :items="tabs">
      <template #lines><p class="p-2 text-sm">Three lines.</p></template>
      <template #history><p class="p-2 text-sm">Placed on 2026-10-01.</p></template>
    </UTabs>

    <UAccordion :items="accordion" />

    <UAlert title="Validation failed" description="Two fields need attention." color="error" variant="subtle" icon="i-lucide-circle-alert" />

    <div class="flex flex-wrap items-center gap-3">
      <UAvatar alt="Ada Lovelace" />
      <UKbd value="K" />
      <UProgress :model-value="40" class="w-40" aria-label="Import progress" />
      <USkeleton class="h-4 w-32" />
    </div>

    <UCard>
      <template #header><h2 class="font-semibold">A card</h2></template>
      <UEmpty title="No dispatch jobs" description="Nothing has been dispatched for this order yet." icon="i-lucide-inbox" />
    </UCard>
  </div>
</template>
