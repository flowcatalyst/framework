<script setup lang="ts">
// ui-kit table-toolbar.md (the platform's FcTableToolbar): quick search on the left; refresh, Clear
// All and a Filters button with the active-filter count on the right; the filter fields in a popover.
// It holds no list state itself: the search is a model (bind useListState's search filter), the
// count is a prop, and Clear All emits `clear` (useListState.clearFilters), then moves focus to the
// search box (the button it was on disappears).
import UBadge from "@nuxt/ui/components/Badge.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import UPopover from "@nuxt/ui/components/Popover.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import { computed, nextTick, useTemplateRef } from "vue";

const {
  searchLabel = "Search",
  searchPlaceholder = "Search…",
  hideSearch = false,
  activeFilterCount = 0,
  hideRefresh = false,
  refreshing = false,
  sizeOptions = [],
} = defineProps<{
  searchLabel?: string;
  searchPlaceholder?: string;
  hideSearch?: boolean;
  /** Active filters besides the search (the badge on the Filters button). */
  activeFilterCount?: number;
  hideRefresh?: boolean;
  /** A refresh is running (the refresh button spins). */
  refreshing?: boolean;
  /** Result sizes for firehose lists (50, 100, ...); empty hides the select. */
  sizeOptions?: readonly number[];
}>();

const search = defineModel<string>("search", { default: "" });
const size = defineModel<number>("size", { default: 0 });

const emit = defineEmits<{
  refresh: [];
  clear: [];
}>();

const slots = defineSlots<{
  /** The filter fields, shown in the Filters popover; without it there is no Filters button. */
  filters?: () => unknown;
  /** Extra controls on the right (a column picker, an export button). */
  actions?: () => unknown;
}>();

const root = useTemplateRef<HTMLElement>("root");
const active = computed(() => search.value !== "" || activeFilterCount > 0);
const sizeItems = computed(() => sizeOptions.map((value) => ({ label: String(value), value })));
const filtersLabel = computed(() => (activeFilterCount > 0 ? `Filters, ${String(activeFilterCount)} active` : "Filters"));

async function clearAll(): Promise<void> {
  emit("clear");
  await nextTick();
  root.value?.querySelector<HTMLInputElement>("input[type=search]")?.focus();
}
</script>

<template>
  <div ref="root" class="flex flex-wrap items-center justify-between gap-2 border-b border-default px-3 py-2" data-testid="fw-table-toolbar">
    <div class="flex min-w-0 items-center gap-2">
      <UInput
        v-if="!hideSearch"
        v-model="search"
        type="search"
        icon="i-lucide-search"
        :placeholder="searchPlaceholder"
        :aria-label="searchLabel"
        class="w-64 max-w-full"
        data-testid="fw-toolbar-search"
      />
    </div>
    <div class="flex items-center gap-2">
      <USelect
        v-if="sizeItems.length > 0"
        v-model="size"
        :items="sizeItems"
        aria-label="Result size"
        class="w-24"
        data-testid="fw-toolbar-size"
      />
      <UButton
        v-if="!hideRefresh"
        icon="i-lucide-refresh-cw"
        color="neutral"
        variant="ghost"
        square
        aria-label="Refresh"
        :loading="refreshing"
        data-testid="fw-toolbar-refresh"
        @click="emit('refresh')"
      />
      <UButton v-if="active" label="Clear all" color="neutral" variant="link" data-testid="fw-toolbar-clear" @click="clearAll" />
      <slot name="actions" />
      <UPopover v-if="slots.filters !== undefined" :content="{ align: 'end', sideOffset: 6 }">
        <UButton icon="i-lucide-list-filter" color="primary" variant="outline" :aria-label="filtersLabel" data-testid="fw-toolbar-filters">
          Filters
          <UBadge v-if="activeFilterCount > 0" :label="activeFilterCount" size="sm" color="primary" variant="solid" aria-hidden="true" />
        </UButton>
        <template #content>
          <div class="flex w-[min(360px,calc(100vw-32px))] flex-col gap-3.5 p-4" data-testid="fw-toolbar-filters-panel">
            <slot name="filters" />
          </div>
        </template>
      </UPopover>
    </div>
  </div>
</template>
