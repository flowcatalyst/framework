<script setup lang="ts">
// ui-kit toggle.md: pressed/unpressed buttons in a group, on Reka UI's ToggleGroup (Nuxt UI has
// none): `exclusive` keeps at most one pressed (the SPA's SelectButton), `independent` any number.
// Each button exposes aria-pressed; the group is named; arrow keys move between buttons (roving
// focus) and Space/Enter toggles.
import UIcon from "@nuxt/ui/components/Icon.vue";
import { ToggleGroupItem, ToggleGroupRoot } from "reka-ui";
import { computed } from "vue";
import type { ToggleOption } from "../types.ts";

const {
  options,
  label,
  kind = "exclusive",
  size = "md",
  iconOnly = false,
} = defineProps<{
  options: readonly ToggleOption[];
  /** The group's accessible name. */
  label: string;
  kind?: "exclusive" | "independent";
  size?: "sm" | "md" | "lg";
  iconOnly?: boolean;
}>();

/** The pressed values (at most one when exclusive). */
const model = defineModel<string[]>({ required: true });

const SIZE = {
  sm: "h-7 gap-1.5 px-2 text-xs",
  md: "h-8 gap-2 px-3 text-sm",
  lg: "h-9 gap-2 px-4 text-sm",
} as const;

const value = computed(() => (kind === "exclusive" ? (model.value[0] ?? "") : model.value));

function update(next: unknown): void {
  const values = (Array.isArray(next) ? next : [next]).filter((v): v is string => typeof v === "string" && v !== "");
  model.value = kind === "exclusive" ? values.slice(0, 1) : values;
}
</script>

<template>
  <ToggleGroupRoot
    :type="kind === 'exclusive' ? 'single' : 'multiple'"
    :model-value="value"
    :aria-label="label"
    class="inline-flex w-fit items-center gap-1 rounded-[2px] border border-default p-1"
    data-testid="fw-toggle-group"
    @update:model-value="update"
  >
    <ToggleGroupItem
      v-for="option in options"
      :key="option.value"
      :value="option.value"
      :disabled="option.disabled === true"
      :aria-label="iconOnly ? option.label : undefined"
      :class="[
        'inline-flex shrink-0 cursor-pointer items-center justify-center rounded-[2px] font-medium whitespace-nowrap text-toned transition-colors select-none',
        'hover:bg-elevated hover:text-highlighted data-[state=on]:bg-accented data-[state=on]:text-highlighted',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary disabled:pointer-events-none disabled:opacity-50',
        SIZE[size],
      ]"
    >
      <UIcon v-if="option.icon !== undefined" :name="option.icon" class="size-4 shrink-0" aria-hidden="true" />
      <span v-if="!iconOnly">{{ option.label }}</span>
    </ToggleGroupItem>
  </ToggleGroupRoot>
</template>
