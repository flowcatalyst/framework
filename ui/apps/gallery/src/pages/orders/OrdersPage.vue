<script setup lang="ts">
// The list-page composition (ui-kit data-table.md) on the generated orders client: URL state from
// useListState, keyset paging from useCursorPagination, the record drawer as a child route, a create
// form in FwFormDialog validated by the generated zPlaceOrderBody. The calls go to the gallery's fake
// server (src/fake-server.ts) through the configured client.
import { ApiRequestError, toServiceError, type ServiceError } from "@flowcatalyst/api-runtime";
import {
  choiceFilter,
  FwCursorPager,
  FwFormDialog,
  FwListPage,
  FwStatusBadge,
  stringFilter,
  useCursorPagination,
  useListState,
  type FwListColumn,
} from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import { computed, h, onMounted, provide, reactive, shallowRef } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { listOrders, placeOrder } from "../../api/orders-demo/sdk.gen.ts";
import type { OrderSummaryView, PlaceOrderBody } from "../../api/orders-demo/types.gen.ts";
import { zPlaceOrderBody } from "../../api/orders-demo/zod.gen.ts";
import { money, REFRESH_ORDERS, STATUS_COLORS } from "./orders.ts";

const route = useRoute();
const router = useRouter();
const list = useListState({}, () => {
  void pager.reset();
});
const search = list.filter(stringFilter("q", { debounce: 250 }));
const status = list.filter(choiceFilter("status", ["OPEN", "CANCELLED"] as const));

const pager = useCursorPagination<OrderSummaryView>({
  fetchPage: async (cursor) => {
    const { data, error, response } = await listOrders({
      query: { limit: 8, ...(status.value === null ? {} : { status: status.value }), ...(cursor === undefined ? {} : { after: cursor }) },
    });
    if (data === undefined) {
      throw new ApiRequestError(toServiceError(error, response));
    }
    return data;
  },
});
provide(REFRESH_ORDERS, () => pager.refresh());
onMounted(() => {
  void pager.loadFirst();
});

const selected = computed(() => {
  const id = route.params["id"];
  return typeof id === "string" ? id : undefined;
});

const columns: FwListColumn<OrderSummaryView>[] = [
  {
    accessorKey: "id",
    header: "Order",
    cell: ({ row }) => h(RouterLink, { to: { path: `/orders/${row.original.id}`, query: route.query }, class: "font-medium text-highlighted underline-offset-2 hover:underline" }, () => row.original.id),
  },
  { accessorKey: "customer", header: "Customer" },
  { accessorKey: "status", header: "Status", cell: ({ row }) => h(FwStatusBadge, { status: row.original.status, colors: STATUS_COLORS }) },
  { accessorKey: "lineCount", header: "Lines", phone: "hide", meta: { class: { td: "text-right tabular-nums", th: "text-right" } } },
  { accessorKey: "totalCents", header: "Total", phone: "hide", cell: ({ row }) => money(row.original.totalCents), meta: { class: { td: "text-right tabular-nums", th: "text-right" } } },
];

const statusItems = [
  { label: "Any status", value: "any" },
  { label: "Open", value: "OPEN" },
  { label: "Cancelled", value: "CANCELLED" },
];
const statusChoice = computed({
  get: () => status.value ?? "any",
  set: (next: string) => {
    status.value = next === "OPEN" || next === "CANCELLED" ? next : null;
  },
});

function open(row: OrderSummaryView): void {
  void router.push({ path: `/orders/${row.id}`, query: route.query });
}

// New order.
const creating = shallowRef(false);
const draft = reactive<PlaceOrderBody>({ customer: "" });
function startCreate(): void {
  draft.customer = "";
  creating.value = true;
}
async function create(body: PlaceOrderBody): Promise<ServiceError | undefined> {
  const { data, error, response } = await placeOrder({ body });
  if (data === undefined) {
    return toServiceError(error, response);
  }
  await pager.reset();
  await router.push({ path: `/orders/${data.id}`, query: route.query });
  return undefined;
}
</script>

<template>
  <FwListPage
    v-model:search="search"
    title="Orders"
    subtitle="FwListPage, FwTableToolbar, FwCursorPager, FwRecordDrawer and FwFormDialog on the generated client"
    :data="pager.items.value"
    :columns="columns"
    :loading="pager.loading.value"
    :active-filter-count="status === null ? 0 : 1"
    :row-key="(row: OrderSummaryView) => row.id"
    :selected-key="selected"
    empty-message="No orders match."
    search-placeholder="Search this page…"
    search-loaded-rows
    data-behaviour="list-url-state list-empty-clear list-page-search list-row-open list-phone-columns"
    @select="open"
    @refresh="pager.refresh"
    @clear="list.clearFilters"
  >
    <template #actions>
      <UButton label="New order" icon="i-lucide-plus" data-testid="new-order" @click="startCreate" />
    </template>
    <template #filters>
      <UFormField label="Status">
        <USelect v-model="statusChoice" :items="statusItems" class="w-full" data-testid="filter-status" />
      </UFormField>
    </template>
    <template #pager>
      <FwCursorPager
        :summary="`Page ${String(pager.page.value)}`"
        :has-prev="pager.hasPrev.value"
        :has-more="pager.hasMore.value"
        :loading="pager.loading.value"
        @newest="pager.loadFirst"
        @newer="pager.loadPrev"
        @older="pager.loadNext"
      />
    </template>
    <template #drawer>
      <p
        class="mt-2 text-sm text-muted"
        data-behaviour="drawer-child-route drawer-non-modal drawer-push drawer-focus drawer-discard form-schema form-server-errors form-busy form-discard form-number-commit form-int64 record-create"
      >
        Open an order for its record drawer (a child route: Back closes it); New order and Add line are form dialogs.
      </p>
      <RouterView />
    </template>
  </FwListPage>
  <FwFormDialog
    v-model:open="creating"
    title="New order"
    description="The customer the order is for."
    submit-label="Create order"
    :schema="zPlaceOrderBody"
    :state="draft"
    :submit="create"
  >
    <UFormField label="Customer" name="customer" required>
      <UInput v-model="draft.customer" class="w-full" data-testid="new-customer" />
    </UFormField>
  </FwFormDialog>
</template>
