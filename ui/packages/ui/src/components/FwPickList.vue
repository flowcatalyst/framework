<script setup lang="ts">
// ui-kit pick-list.md (the SPA's Manage Roles / Application Access pickers): checkboxes on the left,
// filtered as you type; what is picked is mirrored on the right with a count, where a remove button
// unpicks it. Real checkboxes, so Tab and Space work and the picked state is announced; rows that do
// not match the filter are removed from the page (and the accessibility tree). The model is the list
// of picked values; a Save button belongs to the surrounding form (dirty when the list changed).
import UButton from "@nuxt/ui/components/Button.vue";
import UCheckbox from "@nuxt/ui/components/Checkbox.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import { computed, shallowRef, useId } from "vue";
import type { PickItem } from "../types.ts";

const {
  items,
  availableTitle,
  selectedTitle = "Selected",
  filterLabel = "Filter",
  emptyText = "Nothing selected",
} = defineProps<{
  items: readonly PickItem[];
  availableTitle: string;
  selectedTitle?: string;
  /** The filter box's label and placeholder. */
  filterLabel?: string;
  emptyText?: string;
}>();

const picked = defineModel<string[]>({ required: true });

const id = useId();
const query = shallowRef("");
const pickedSet = computed(() => new Set(picked.value));
const visible = computed(() => {
  const q = query.value.trim().toLowerCase();
  return q === "" ? items : items.filter((item) => `${item.label} ${item.code ?? ""}`.toLowerCase().includes(q));
});
const pickedItems = computed(() => items.filter((item) => pickedSet.value.has(item.value)));

function toggle(item: PickItem, on: boolean | "indeterminate"): void {
  const next = picked.value.filter((value) => value !== item.value);
  picked.value = on === true ? items.filter((i) => i.value === item.value || next.includes(i.value)).map((i) => i.value) : next;
}
</script>

<template>
  <div class="grid grid-cols-1 gap-4 sm:grid-cols-2" data-testid="fw-pick-list">
    <section class="flex min-w-0 flex-col overflow-hidden rounded-[2px] border border-default" :aria-labelledby="`${id}-available`">
      <header class="flex flex-col gap-2 border-b border-default bg-muted px-3 py-2">
        <h4 :id="`${id}-available`" class="text-xs font-semibold tracking-wide text-toned uppercase">{{ availableTitle }}</h4>
        <UInput v-model="query" type="search" icon="i-lucide-search" size="sm" :placeholder="filterLabel" :aria-label="filterLabel" data-testid="fw-pick-filter" />
      </header>
      <ul class="h-[200px] overflow-y-auto p-1" data-testid="fw-pick-available">
        <li v-for="item in visible" :key="item.value" class="rounded-[2px] px-2 py-1.5 hover:bg-elevated has-[[data-state=checked]]:bg-primary/10">
          <UCheckbox
            :model-value="pickedSet.has(item.value)"
            :label="item.label"
            :description="[item.code, item.note].filter((part) => part !== undefined).join(' · ')"
            @update:model-value="(on) => toggle(item, on)"
          />
        </li>
        <li v-if="visible.length === 0" class="px-2 py-1.5 text-sm text-muted">No match</li>
      </ul>
    </section>
    <section class="flex min-w-0 flex-col overflow-hidden rounded-[2px] border border-default" :aria-labelledby="`${id}-selected`">
      <header class="border-b border-default bg-muted px-3 py-2">
        <h4 :id="`${id}-selected`" class="text-xs font-semibold tracking-wide text-toned uppercase">
          {{ selectedTitle }} <span aria-live="polite" data-testid="fw-pick-count">({{ pickedItems.length }})</span>
        </h4>
      </header>
      <ul class="h-[200px] overflow-y-auto p-1" data-testid="fw-pick-selected">
        <li v-for="item in pickedItems" :key="item.value" class="flex items-center justify-between gap-2 rounded-[2px] px-2 py-1">
          <span class="min-w-0 truncate text-sm">{{ item.label }}</span>
          <UButton
            icon="i-lucide-x"
            size="xs"
            color="neutral"
            variant="ghost"
            square
            :aria-label="`Remove ${item.label}`"
            @click="toggle(item, false)"
          />
        </li>
        <li v-if="pickedItems.length === 0" class="px-2 py-1.5 text-sm text-muted">{{ emptyText }}</li>
      </ul>
    </section>
  </div>
</template>
