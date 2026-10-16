<script setup lang="ts">
// Event types of this application, as the platform holds them (M4e slice 2): a read-only list over the
// generated listAdminEventTypes, the record drawer as a child route. The search and the status filter
// go to the server (it pages by cursor); the platform's refusals are shown on the page in the server's
// words, not as an empty list.
import {
  choiceFilter,
  FwCodeChips,
  FwCursorPager,
  FwListPage,
  FwStatusBadge,
  stringFilter,
  useCursorPagination,
  useListState,
  type FwListColumn,
} from "@flowcatalyst/ui";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import { computed, h, onMounted, shallowRef } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { listAdminEventTypes } from "../../api/orders/sdk.gen.ts";
import type { AdminEventType } from "../../api/orders/types.gen.ts";
import { dataOrThrow, EVENT_TYPE_COLORS, failureText } from "./platform.ts";

const route = useRoute();
const router = useRouter();
const failure = shallowRef<string | undefined>(undefined);

const list = useListState({}, () => {
  void pager.reset();
});
const search = list.filter(stringFilter("q", { debounce: 250 }));
const status = list.filter(choiceFilter("status", ["CURRENT", "ARCHIVED"] as const));

const pager = useCursorPagination<AdminEventType>({
  fetchPage: async (cursor) => {
    const page = dataOrThrow(
      await listAdminEventTypes({
        query: {
          limit: 8,
          ...(status.value === null ? {} : { status: status.value }),
          ...(search.value === "" ? {} : { q: search.value }),
          ...(cursor === undefined ? {} : { after: cursor }),
        },
      }),
    );
    failure.value = undefined;
    return page;
  },
  onError: (cause) => {
    failure.value = failureText(cause, "The event types");
  },
});
onMounted(() => {
  void pager.loadFirst();
});

const selected = computed(() => {
  const id = route.params["id"];
  return typeof id === "string" ? id : undefined;
});

const columns: FwListColumn<AdminEventType>[] = [
  {
    accessorKey: "code",
    header: "Code",
    cell: ({ row }) =>
      h(
        RouterLink,
        { to: { path: `/admin/event-types/${row.original.id}`, query: route.query }, class: "underline-offset-2 hover:underline" },
        () => h(FwCodeChips, { code: row.original.code }),
      ),
  },
  { accessorKey: "name", header: "Name", phone: "hide" },
  { accessorKey: "status", header: "Status", cell: ({ row }) => h(FwStatusBadge, { status: row.original.status, colors: EVENT_TYPE_COLORS }) },
  { accessorKey: "versionCount", header: "Versions", phone: "hide", meta: { class: { td: "text-right tabular-nums", th: "text-right" } } },
];

const statusItems = [
  { label: "Any status", value: "any" },
  { label: "Current", value: "CURRENT" },
  { label: "Archived", value: "ARCHIVED" },
];
const statusChoice = computed({
  get: () => status.value ?? "any",
  set: (next: string) => {
    status.value = next === "CURRENT" || next === "ARCHIVED" ? next : null;
  },
});

function open(row: AdminEventType): void {
  void router.push({ path: `/admin/event-types/${row.id}`, query: route.query });
}
</script>

<template>
  <div class="space-y-4">
    <UAlert
      v-if="failure !== undefined"
      color="error"
      variant="subtle"
      title="The platform could not be read"
      :description="failure"
      data-testid="platform-error"
    />
    <FwListPage
      v-model:search="search"
      title="Event types"
      subtitle="The event types this application publishes, as the platform holds them"
      :data="pager.items.value"
      :columns="columns"
      :loading="pager.loading.value"
      :active-filter-count="status === null ? 0 : 1"
      :row-key="(row: AdminEventType) => row.id"
      :selected-key="selected"
      empty-message="No event types match."
      search-placeholder="Search code, name or description…"
      @select="open"
      @refresh="pager.refresh"
      @clear="list.clearFilters"
    >
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
        <RouterView />
      </template>
    </FwListPage>
  </div>
</template>
