<script setup lang="ts">
import { FwCursorPager, FwPaginator, FwPickList, FwTableToolbar, useCursorPagination, type PickItem } from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import USwitch from "@nuxt/ui/components/Switch.vue";
import { computed, onMounted, shallowRef } from "vue";
import GallerySection from "../components/GallerySection.vue";

// Table toolbar: state owned here (an application binds useListState instead).
const search = shallowRef("");
const status = shallowRef("any");
const archived = shallowRef(false);
const size = shallowRef(100);
const refreshes = shallowRef(0);
const activeFilters = computed(() => (status.value === "any" ? 0 : 1) + (archived.value ? 1 : 0));
const statusItems = [
  { label: "Any", value: "any" },
  { label: "Open", value: "OPEN" },
  { label: "Cancelled", value: "CANCELLED" },
];
function clearAll(): void {
  search.value = "";
  status.value = "any";
  archived.value = false;
}

// Cursor pager: 7 events, 3 a page, from a fake keyset source. Older pages answer slowly (600 ms), the
// first quickly (60 ms): "Older" then "Start again" at once shows that the slow answer is dropped.
const events = Array.from({ length: 7 }, (_, i) => `evt_${String(100 - i)}`);
const pager = useCursorPagination<string>({
  fetchPage: async (cursor) => {
    await new Promise((resolve) => setTimeout(resolve, cursor === undefined ? 60 : 600));
    const start = cursor === undefined ? 0 : Number(cursor);
    const next = start + 3 < events.length ? String(start + 3) : undefined;
    return { items: events.slice(start, start + 3), hasMore: next !== undefined, ...(next === undefined ? {} : { nextCursor: next }) };
  },
});
onMounted(() => {
  void pager.loadFirst();
});

// Paginator.
const page = shallowRef(1);
const pageSize = shallowRef(50);

// Pick list.
const roles: PickItem[] = [
  { value: "orders:clerk", label: "Order clerk", code: "orders:clerk" },
  { value: "orders:admin", label: "Order administrator", code: "orders:admin" },
  { value: "billing:viewer", label: "Billing viewer", code: "billing:viewer" },
  { value: "billing:admin", label: "Billing administrator", code: "billing:admin", note: "Inactive" },
  { value: "support:agent", label: "Support agent", code: "support:agent" },
];
const picked = shallowRef<string[]>(["orders:clerk"]);
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="List parts" description="Table toolbar, cursor pager, paginator and pick list: FlowCatalyst list behaviour on Nuxt UI parts" />

    <GallerySection :behaviours="['list-filter-count', 'list-clear-all']" title="Table toolbar (FwTableToolbar)" description="Search left; size, refresh, Clear all and Filters (with the active count) right. Clear all moves focus to the search box.">
      <div class="rounded-[2px] border border-default">
        <FwTableToolbar v-model:search="search" v-model:size="size" :size-options="[50, 100, 200, 500]" :active-filter-count="activeFilters" @refresh="refreshes += 1" @clear="clearAll">
          <template #filters>
            <UFormField label="Status">
              <USelect v-model="status" :items="statusItems" class="w-full" data-testid="filter-status" />
            </UFormField>
            <USwitch v-model="archived" label="Include archived" data-testid="filter-archived" />
          </template>
        </FwTableToolbar>
        <p class="px-3 py-2 text-sm text-muted" data-testid="toolbar-state">search="{{ search }}" status={{ status }} archived={{ archived }} size={{ size }} refreshes={{ refreshes }}</p>
      </div>
    </GallerySection>

    <GallerySection :behaviours="['cursor-paging', 'cursor-race']" title="Cursor pager (FwCursorPager + useCursorPagination)" description="Newest, Newer (from the in-session stack) and Older; an unusable control stays focusable (aria-disabled). Older pages answer slowly here: Older then Start again shows the first page, not the late answer.">
      <div class="rounded-[2px] border border-default">
        <div class="flex items-start justify-between gap-2 px-3 py-2">
          <ul class="text-sm" data-testid="pager-items">
            <li v-for="item in pager.items.value" :key="item">{{ item }}</li>
          </ul>
          <UButton label="Start again" size="sm" color="neutral" variant="ghost" data-testid="pager-restart" @click="pager.reset" />
        </div>
        <FwCursorPager
          :summary="`Page ${String(pager.page.value)}`"
          :has-prev="pager.hasPrev.value"
          :has-more="pager.hasMore.value"
          :loading="pager.loading.value"
          @newest="pager.loadFirst"
          @newer="pager.loadPrev"
          @older="pager.loadNext"
        />
      </div>
    </GallerySection>

    <GallerySection :behaviours="['offset-paging']" title="Paginator (FwPaginator)" description="UPagination with a rows-per-page USelect and the report.">
      <div class="rounded-[2px] border border-default">
        <FwPaginator v-model:page="page" v-model:page-size="pageSize" :total="1234" noun="orders" />
      </div>
    </GallerySection>

    <GallerySection :behaviours="['pick-list']" title="Pick list (FwPickList)" description="Tick on the left (filtered as you type), mirrored on the right with a count; remove from the right.">
      <FwPickList v-model="picked" :items="roles" available-title="Available roles" filter-label="Filter roles" />
      <p class="text-sm text-muted">Picked: <span data-testid="picked">{{ picked.join(", ") }}</span></p>
    </GallerySection>
  </div>
</template>
