<script setup lang="ts">
// ui-kit drawer.md (the SPA's EntityDrawer): a NON-modal panel on the right over a list. No mask, no
// focus trap: the list stays scrollable and clickable, and clicking another row switches the record.
// On a wide screen it pushes the list aside (the list page makes room; lib/drawerHost.ts), so Filters,
// Refresh and the columns stay usable; too narrow for both, it overlays the list.
// Closed by its X or Escape (Escape closes an open menu or dialog inside it first). When it opens,
// focus moves to its heading. Usually the component of a child route (useDrawerRoute): the drawer's
// `close` navigates back to the list, Back closes it, a deep link opens it.
import USlideover from "@nuxt/ui/components/Slideover.vue";
import { useWindowSize } from "@vueuse/core";
import { computed, inject, nextTick, onBeforeUnmount, useTemplateRef, watchEffect } from "vue";
import { DRAWER_HOST_KEY } from "../lib/drawerHost.ts";
import type { DrawerSize } from "../types.ts";

const {
  open,
  title,
  subtitle,
  size = "default",
} = defineProps<{
  open: boolean;
  title: string;
  subtitle?: string;
  size?: DrawerSize;
}>();

const emit = defineEmits<{
  /** The user asked to close it (X, Escape). The owner closes it (or asks to discard changes first). */
  close: [];
}>();

const slots = defineSlots<{
  /** Status tags beside the title. */
  extras?: () => unknown;
  default?: () => unknown;
  /** Actions at the bottom (Edit, Save). */
  footer?: () => unknown;
}>();

const heading = useTemplateRef<HTMLElement>("heading");

// Tell the list page how wide the drawer is while it is open, so the list can make room (G5).
const host = inject(DRAWER_HOST_KEY, undefined);
const { width: windowWidth } = useWindowSize();
const pixels = computed(() => {
  const room = windowWidth.value - 24;
  return size === "default" ? Math.min(560, room) : size === "wide" ? Math.min(800, room) : Math.round(windowWidth.value * 0.66667);
});
watchEffect(() => {
  if (host !== undefined) {
    host.width.value = open ? pixels.value : undefined;
  }
});
onBeforeUnmount(() => {
  if (host !== undefined) {
    host.width.value = undefined;
  }
});

const SIZE: Readonly<Record<DrawerSize, string>> = {
  default: "max-w-[min(560px,calc(100vw-24px))]",
  wide: "max-w-[min(800px,calc(100vw-24px))]",
  "two-thirds": "max-w-[66.667vw]",
};
const ui = computed(() => ({
  content: `w-full ${SIZE[size]} shadow-[-8px_0_32px_rgb(15_23_42/0.12)]`,
  title: "text-lg font-semibold truncate",
  description: "text-[13px] text-muted",
}));

const content = {
  // Non-modal: a click on the list must not close it (another row switches the record instead).
  onInteractOutside: (event: Event) => {
    event.preventDefault();
  },
  onOpenAutoFocus: (event: Event) => {
    event.preventDefault();
    void nextTick(() => {
      heading.value?.focus();
    });
  },
};

function onOpenChange(next: boolean): void {
  if (!next) {
    emit("close");
  }
}
</script>

<template>
  <USlideover
    :open="open"
    :modal="false"
    :overlay="false"
    side="right"
    :title="title"
    :description="subtitle ?? ''"
    :content="content"
    :ui="ui"
    @update:open="onOpenChange"
  >
    <template #title>
      <span ref="heading" tabindex="-1" class="outline-none" data-testid="fw-drawer-title">{{ title }}</span>
    </template>
    <template v-if="subtitle !== undefined" #description>{{ subtitle }}</template>
    <template v-if="slots.extras !== undefined" #actions>
      <div class="flex items-center gap-2"><slot name="extras" /></div>
    </template>
    <template #body>
      <slot />
    </template>
    <template v-if="slots.footer !== undefined" #footer>
      <div class="flex w-full items-center justify-end gap-2"><slot name="footer" /></div>
    </template>
  </USlideover>
</template>
