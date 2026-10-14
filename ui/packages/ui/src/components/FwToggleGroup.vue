<script setup lang="ts">
// Pressed/unpressed buttons in a group (ui-kit toggle.md; the SPA's SelectButton): Nuxt UI's buttons in
// a UFieldGroup, with the behaviour of Reka UI's ToggleGroup, which Nuxt UI has no component for:
// `exclusive` keeps at most one pressed, `independent` any number; each button exposes aria-pressed;
// the group is named; arrow keys move between the buttons (roving focus), Space and Enter toggle.
// A pressed button takes the `active` look (primary, solid).
import UButton from "@nuxt/ui/components/Button.vue";
import UFieldGroup from "@nuxt/ui/components/FieldGroup.vue";
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
  size?: "xs" | "sm" | "md" | "lg" | "xl";
  iconOnly?: boolean;
}>();

/** The pressed values (at most one when exclusive). */
const model = defineModel<string[]>({ required: true });

const value = computed(() => (kind === "exclusive" ? (model.value[0] ?? "") : model.value));

function update(next: unknown): void {
  const values = (Array.isArray(next) ? next : [next]).filter((v): v is string => typeof v === "string" && v !== "");
  model.value = kind === "exclusive" ? values.slice(0, 1) : values;
}
</script>

<template>
  <ToggleGroupRoot :type="kind === 'exclusive' ? 'single' : 'multiple'" :model-value="value" as-child @update:model-value="update">
    <UFieldGroup :aria-label="label" data-testid="fw-toggle-group">
      <ToggleGroupItem v-for="option in options" :key="option.value" :value="option.value" :disabled="option.disabled === true" as-child>
        <UButton
          v-bind="{ ...(iconOnly ? { 'aria-label': option.label } : { label: option.label }), ...(option.icon === undefined ? {} : { icon: option.icon }) }"
          :size="size"
          :square="iconOnly"
          color="neutral"
          variant="outline"
          :active="model.includes(option.value)"
          active-color="primary"
          active-variant="solid"
        />
      </ToggleGroupItem>
    </UFieldGroup>
  </ToggleGroupRoot>
</template>
