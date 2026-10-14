<script setup lang="ts">
import { FwPageHeader, FwRowActions, FwToggleGroup, useConfirm, useFlash, type RowAction, type ToggleOption } from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UDropdownMenu from "@nuxt/ui/components/DropdownMenu.vue";
import type { DropdownMenuItem } from "@nuxt/ui/components/DropdownMenu.vue";
import UKbd from "@nuxt/ui/components/Kbd.vue";
import UTooltip from "@nuxt/ui/components/Tooltip.vue";
import { shallowRef } from "vue";
import GallerySection from "../components/GallerySection.vue";

const variants = ["solid", "outline", "soft", "subtle", "ghost", "link"] as const;
const colors = ["primary", "neutral", "error"] as const;
const lastAction = shallowRef("none");

const menuItems: DropdownMenuItem[][] = [
  [
    { label: "Edit", icon: "i-lucide-pencil", onSelect: () => (lastAction.value = "edit") },
    { label: "Duplicate", icon: "i-lucide-copy", onSelect: () => (lastAction.value = "duplicate") },
  ],
  [{ label: "Delete…", icon: "i-lucide-trash", color: "error", onSelect: () => (lastAction.value = "delete") }],
];

const view = shallowRef<string[]>(["table"]);
const views: ToggleOption[] = [
  { value: "table", label: "Table", icon: "i-lucide-table" },
  { value: "cards", label: "Cards", icon: "i-lucide-layout-grid" },
  { value: "timeline", label: "Timeline", icon: "i-lucide-list" },
];
const formatting = shallowRef<string[]>(["bold"]);
const formats: ToggleOption[] = [
  { value: "bold", label: "Bold", icon: "i-lucide-bold" },
  { value: "italic", label: "Italic", icon: "i-lucide-italic" },
  { value: "underline", label: "Underline", icon: "i-lucide-underline", disabled: true },
];

const confirm = useConfirm();
const flash = useFlash();
const rows = [
  { id: "pool-orders", name: "Orders pool", state: "running" },
  { id: "pool-billing", name: "Billing pool", state: "paused" },
] as const;

function actionsFor(row: (typeof rows)[number]): { inline: RowAction[]; menu: RowAction[] } {
  return {
    inline: [
      { label: "Edit", icon: "i-lucide-pencil", onSelect: () => (lastAction.value = `edit ${row.id}`) },
      row.state === "running"
        ? { label: "Pause", icon: "i-lucide-pause", onSelect: () => (lastAction.value = `pause ${row.id}`) }
        : { label: "Pause", icon: "i-lucide-pause", disabledReason: "Already paused" },
    ],
    menu: [
      { label: "View dispatch jobs", icon: "i-lucide-list", to: "/orders" },
      { label: "Resume", icon: "i-lucide-play", ...(row.state === "paused" ? { onSelect: () => (lastAction.value = `resume ${row.id}`) } : { disabledReason: "The pool is running" }) },
      {
        label: "Delete…",
        icon: "i-lucide-trash",
        destructive: true,
        onSelect: () => {
          void remove(row.name);
        },
      },
    ],
  };
}

async function remove(name: string): Promise<void> {
  const confirmed = await confirm({ title: `Delete ${name}?`, message: "Its dispatch history is kept; the pool stops at once.", confirmLabel: "Delete pool", tone: "danger" });
  lastAction.value = confirmed ? `deleted ${name}` : `kept ${name}`;
  if (confirmed) {
    flash.success(`${name} deleted.`);
  }
}
</script>

<template>
  <div class="space-y-6">
    <FwPageHeader title="Actions" subtitle="Buttons, menus, toggles, row actions, tooltips and keyboard hints" />

    <GallerySection spec="button" title="Button" description="Every variant in the primary, neutral and error colours; sizes; loading; disabled; icon only.">
      <div v-for="color in colors" :key="color" class="flex flex-wrap items-center gap-2">
        <UButton v-for="variant in variants" :key="variant" :label="`${variant} ${color}`" :color="color" :variant="variant" />
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <UButton label="Small" size="sm" />
        <UButton label="Medium" size="md" />
        <UButton label="Large" size="lg" />
        <UButton label="Saving" loading />
        <UButton label="Disabled" disabled />
        <UButton icon="i-lucide-refresh-cw" square aria-label="Refresh" color="neutral" variant="ghost" />
      </div>
    </GallerySection>

    <GallerySection spec="dropdown-menu" title="Dropdown menu" description="Groups, icons, a destructive item last.">
      <div class="flex items-center gap-3">
        <UDropdownMenu :items="menuItems">
          <UButton label="Actions" trailing-icon="i-lucide-chevron-down" color="neutral" variant="outline" data-testid="open-menu" />
        </UDropdownMenu>
        <span class="text-sm text-muted">Last action: <span data-testid="last-action">{{ lastAction }}</span></span>
      </div>
    </GallerySection>

    <GallerySection spec="toggle" title="Toggle group (FwToggleGroup)" description="Exclusive (one pressed) and independent; arrow keys move, Space toggles.">
      <div class="flex flex-wrap items-center gap-4">
        <FwToggleGroup v-model="view" :options="views" label="View" kind="exclusive" data-testid="toggle-view" />
        <FwToggleGroup v-model="formatting" :options="formats" label="Formatting" kind="independent" icon-only size="sm" />
        <span class="text-sm text-muted">View: <span data-testid="toggle-view-value">{{ view.join(",") }}</span>; formatting: {{ formatting.join(",") }}</span>
      </div>
    </GallerySection>

    <GallerySection spec="row-actions" title="Row actions (FwRowActions)" description="Two inline actions at most, the rest in a menu; a disabled action keeps focus and says why; destructive last, confirmed first.">
      <table class="w-full text-sm">
        <thead>
          <tr class="text-left text-muted"><th class="py-2 font-medium">Pool</th><th class="py-2 font-medium">State</th><th class="py-2 text-right font-medium">Actions</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" class="border-t border-default">
            <td class="py-2">{{ row.name }}</td>
            <td class="py-2">{{ row.state }}</td>
            <td class="w-px py-2 whitespace-nowrap">
              <FwRowActions :row-label="row.name" :actions="actionsFor(row).inline" :menu="actionsFor(row).menu" />
            </td>
          </tr>
        </tbody>
      </table>
    </GallerySection>

    <GallerySection spec="tooltip" title="Tooltip">
      <UTooltip text="Copies the order id">
        <UButton icon="i-lucide-clipboard" aria-label="Copy order id" color="neutral" variant="ghost" data-testid="tooltip-trigger" />
      </UTooltip>
    </GallerySection>

    <GallerySection spec="kbd" title="Keyboard hint">
      <p class="text-sm">Search with <UKbd value="meta" /> <UKbd value="K" />, close with <UKbd value="Esc" />.</p>
    </GallerySection>
  </div>
</template>
