<script setup lang="ts">
// ui-kit row-actions.md: the trailing cell of a list row. At most two frequent, safe actions inline
// as icon buttons (each named with the row: "Edit Orders pool"); everything else in a "More actions
// for {row}" menu, destructive actions last and separated, in red, with a text label. An action the
// user may not run on this row is shown disabled WITH its reason (aria-disabled, so it keeps focus and
// the reason is its description); an action the user may never run is left out by the caller.
//
// The menu is Nuxt UI's dropdown menu (an ARIA menu with arrow-key focus), not the kit's popover
// disclosure; activating an item closes the menu and returns focus to its trigger first.
import type { DropdownMenuItem } from "@nuxt/ui/components/DropdownMenu.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UDropdownMenu from "@nuxt/ui/components/DropdownMenu.vue";
import UTooltip from "@nuxt/ui/components/Tooltip.vue";
import { computed, useId } from "vue";
import type { RowAction } from "../types.ts";

const { rowLabel, actions = [], menu = [] } = defineProps<{
  /** The row's name, appended to every accessible name. */
  rowLabel: string;
  /** Inline icon buttons (at most two). */
  actions?: readonly RowAction[];
  /** The overflow menu; empty hides it. */
  menu?: readonly RowAction[];
}>();

const id = useId();
const inline = computed(() => actions.slice(0, 2));

function isDisabled(action: RowAction): boolean {
  return action.disabledReason !== undefined && action.disabledReason !== "";
}

function toItem(action: RowAction): DropdownMenuItem {
  const disabled = isDisabled(action);
  return {
    label: action.label,
    ...(action.icon === undefined ? {} : { icon: action.icon }),
    ...(action.destructive === true ? { color: "error" as const } : {}),
    ...(disabled
      ? { disabled: true, description: action.disabledReason ?? "" }
      : {
          ...(action.to === undefined ? {} : { to: action.to }),
          ...(action.onSelect === undefined ? {} : { onSelect: action.onSelect }),
        }),
  };
}

const items = computed<DropdownMenuItem[][]>(() => {
  const safe = menu.filter((action) => action.destructive !== true).map(toItem);
  const destructive = menu.filter((action) => action.destructive === true).map(toItem);
  return [safe, destructive].filter((group) => group.length > 0);
});

function run(action: RowAction): void {
  if (!isDisabled(action)) {
    action.onSelect?.();
  }
}
</script>

<template>
  <div class="flex items-center justify-end gap-1" data-testid="fw-row-actions">
    <template v-for="(action, index) in inline" :key="action.label">
      <UTooltip :text="isDisabled(action) ? `${action.label}: ${action.disabledReason ?? ''}` : action.label">
        <UButton
          v-if="isDisabled(action)"
          :icon="action.icon ?? 'i-lucide-circle'"
          color="neutral"
          variant="ghost"
          size="sm"
          square
          :aria-label="`${action.label} ${rowLabel}`"
          aria-disabled="true"
          :aria-describedby="`${id}-a${String(index)}`"
          class="cursor-not-allowed opacity-50"
        />
        <UButton
          v-else
          :icon="action.icon ?? 'i-lucide-circle'"
          :color="action.destructive === true ? 'error' : 'neutral'"
          variant="ghost"
          size="sm"
          square
          :aria-label="`${action.label} ${rowLabel}`"
          v-bind="action.to === undefined ? {} : { to: action.to }"
          @click="run(action)"
        />
      </UTooltip>
      <span v-if="isDisabled(action)" :id="`${id}-a${String(index)}`" hidden>{{ action.disabledReason }}</span>
    </template>
    <!-- Not modal: the kit's row menu is a disclosure, and a modal menu hides the page with
         aria-hidden while focus can still reach it (axe aria-hidden-focus). -->
    <UDropdownMenu v-if="items.length > 0" :items="items" :modal="false" :content="{ align: 'end' }">
      <UButton
        icon="i-lucide-ellipsis"
        color="neutral"
        variant="ghost"
        size="sm"
        square
        :aria-label="`More actions for ${rowLabel}`"
        data-testid="fw-row-actions-menu"
      />
    </UDropdownMenu>
  </div>
</template>
