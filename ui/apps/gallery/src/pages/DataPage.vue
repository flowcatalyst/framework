<script setup lang="ts">
import { FwPageHeader, FwStatusBadge, type SortingState, type TagSeverity } from "@flowcatalyst/ui";
import UAccordion from "@nuxt/ui/components/Accordion.vue";
import UAvatar from "@nuxt/ui/components/Avatar.vue";
import UBreadcrumb from "@nuxt/ui/components/Breadcrumb.vue";
import UCard from "@nuxt/ui/components/Card.vue";
import UPagination from "@nuxt/ui/components/Pagination.vue";
import USeparator from "@nuxt/ui/components/Separator.vue";
import UTable from "@nuxt/ui/components/Table.vue";
import type { TableColumn } from "@nuxt/ui/components/Table.vue";
import UTabs from "@nuxt/ui/components/Tabs.vue";
import { h, ref } from "vue";
import GallerySection from "../components/GallerySection.vue";

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

const severities: Readonly<Record<Order["status"], TagSeverity>> = { OPEN: "info", PLACED: "success", CANCELLED: "secondary" };

const columns: TableColumn<Order>[] = [
  { accessorKey: "id", header: "Order" },
  { accessorKey: "customer", header: "Customer" },
  { accessorKey: "status", header: "Status", cell: ({ row }) => h(FwStatusBadge, { status: row.original.status, severities }) },
  { accessorKey: "total", header: "Total", cell: ({ row }) => row.original.total.toFixed(2), meta: { class: { td: "text-right tabular-nums", th: "text-right" } } },
];

// UTable's `sorting` model cannot be typed under exactOptionalPropertyTypes (docs/ui/u0.md); the
// binding is split. FwListPage does this once for list pages.
const sorting = ref<SortingState>([{ id: "customer", desc: false }]);
const page = ref(1);
const tabs = [
  { label: "Lines", slot: "lines" as const },
  { label: "History", slot: "history" as const },
  { label: "Disabled", slot: "lines" as const, disabled: true },
];
const accordion = [
  { label: "Shipping", content: "Ships from the Rotterdam warehouse." },
  { label: "Billing", content: "Invoiced monthly." },
];
const crumbs = [{ label: "Orders", to: "/orders" }, { label: "ord_1042" }];
</script>

<template>
  <div class="space-y-6">
    <FwPageHeader title="Data display" subtitle="Table, pagination, tabs, accordion, card, avatar, breadcrumb and separator" />

    <GallerySection spec="table" title="Table" description="UTable with a sortable column and a status cell (FwStatusBadge).">
      <!-- Scrolls sideways on a phone: a focusable, named region (FwListPage does the same). -->
      <div class="overflow-x-auto" tabindex="0" role="region" aria-label="Orders table">
        <UTable :sorting="sorting" :data="orders" :columns="columns" caption="Orders" :ui="{ root: 'overflow-visible' }" data-testid="orders-table" @update:sorting="(value) => (sorting = value ?? [])" />
      </div>
    </GallerySection>

    <GallerySection spec="pagination" title="Pagination">
      <UPagination v-model:page="page" :total="40" :items-per-page="10" show-edges />
    </GallerySection>

    <GallerySection spec="tabs" title="Tabs" description="The kit's tabs: a bordered list, the current tab on a neutral tint.">
      <UTabs :items="tabs">
        <template #lines><p class="p-2 text-sm">Three lines.</p></template>
        <template #history><p class="p-2 text-sm">Placed on 2026-10-01.</p></template>
      </UTabs>
    </GallerySection>

    <GallerySection spec="accordion" title="Accordion">
      <UAccordion :items="accordion" />
    </GallerySection>

    <GallerySection spec="card avatar" title="Card and avatar">
      <UCard class="max-w-md">
        <template #header><h3 class="font-semibold">Ada Lovelace</h3></template>
        <div class="flex items-center gap-3">
          <UAvatar alt="Ada Lovelace" />
          <UAvatar src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 8 8'%3E%3Crect width='8' height='8' fill='%23486581'/%3E%3C/svg%3E" alt="Navy square" />
          <p class="text-sm text-muted">Initials, or an image from <code>img-src 'self' data:</code>.</p>
        </div>
      </UCard>
    </GallerySection>

    <GallerySection spec="breadcrumb separator" title="Breadcrumb and separator">
      <UBreadcrumb :items="crumbs" />
      <USeparator />
      <USeparator label="or" />
    </GallerySection>
  </div>
</template>
