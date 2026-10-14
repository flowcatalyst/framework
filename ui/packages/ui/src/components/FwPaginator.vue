<script setup lang="ts">
// ui-kit paginator.md: numbered pages (Nuxt UI's pagination: first, previous, a window of pages, next,
// last), a rows-per-page select and the report "Showing x to y of n noun". The page and size are
// models, so useListState can own them (and the URL).
import UPagination from "@nuxt/ui/components/Pagination.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import { computed } from "vue";

const {
  total,
  noun,
  pageSizeOptions = [50, 100, 250, 500],
} = defineProps<{
  /** Records in the view (all pages). */
  total: number;
  /** The report's noun (`orders`). */
  noun: string;
  pageSizeOptions?: readonly number[];
}>();

/** 1-based. */
const page = defineModel<number>("page", { required: true });
const pageSize = defineModel<number>("pageSize", { required: true });

const first = computed(() => (total === 0 ? 0 : (page.value - 1) * pageSize.value + 1));
const last = computed(() => Math.min(total, page.value * pageSize.value));
const sizeItems = computed(() => pageSizeOptions.map((size) => ({ label: String(size), value: size })));

function setSize(next: number): void {
  pageSize.value = next;
  page.value = 1;
}
</script>

<template>
  <div class="flex flex-wrap items-center justify-center gap-3 border-t border-default px-3 py-2 text-sm text-muted" data-testid="fw-paginator">
    <UPagination v-model:page="page" :total="total" :items-per-page="pageSize" show-edges :sibling-count="1" size="sm" />
    <USelect :model-value="pageSize" :items="sizeItems" size="sm" aria-label="Rows per page" class="w-20" @update:model-value="setSize" />
    <span data-testid="fw-paginator-report">Showing {{ first }} to {{ last }} of {{ total }} {{ noun }}</span>
  </div>
</template>
