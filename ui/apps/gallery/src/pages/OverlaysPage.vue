<script setup lang="ts">
import { FwPageHeader } from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UDropdownMenu from "@nuxt/ui/components/DropdownMenu.vue";
import type { DropdownMenuItem } from "@nuxt/ui/components/DropdownMenu.vue";
import UModal from "@nuxt/ui/components/Modal.vue";
import UPopover from "@nuxt/ui/components/Popover.vue";
import USlideover from "@nuxt/ui/components/Slideover.vue";
import UTooltip from "@nuxt/ui/components/Tooltip.vue";
import { useToast } from "@nuxt/ui/composables/useToast";
import { ref } from "vue";

const toast = useToast();
const lastAction = ref("none");

const menuItems: DropdownMenuItem[][] = [
  [
    { label: "Edit", icon: "i-lucide-pencil", onSelect: () => (lastAction.value = "edit") },
    { label: "Duplicate", icon: "i-lucide-copy", onSelect: () => (lastAction.value = "duplicate") },
  ],
  [{ label: "Delete", icon: "i-lucide-trash", color: "error", onSelect: () => (lastAction.value = "delete") }],
];

function showToast(): void {
  toast.add({ title: "Order saved", description: "Order 1042 was saved.", color: "success", icon: "i-lucide-check" });
}
</script>

<template>
  <div class="space-y-6">
    <FwPageHeader title="Overlays" subtitle="Modal, slideover, popover, tooltip, dropdown menu and toast" />

    <section class="flex flex-wrap items-center gap-3">
      <UModal title="Discard changes?" description="Your edits to this order are not saved.">
        <UButton label="Open modal" color="neutral" variant="outline" data-testid="open-modal" />
        <template #body>
          <p class="text-sm">The modal traps focus and closes with Escape.</p>
        </template>
        <template #footer="{ close }">
          <UButton label="Keep editing" color="neutral" variant="outline" data-testid="modal-close" @click="close" />
        </template>
      </UModal>

      <USlideover title="Order 1042" description="A record drawer is a slideover on the right.">
        <UButton label="Open slideover" color="neutral" variant="outline" data-testid="open-slideover" />
        <template #body>
          <dl class="grid grid-cols-2 gap-2 text-sm">
            <dt class="text-muted">Status</dt>
            <dd>Open</dd>
            <dt class="text-muted">Lines</dt>
            <dd>3</dd>
          </dl>
        </template>
      </USlideover>

      <UPopover>
        <UButton label="Open popover" color="neutral" variant="outline" data-testid="open-popover" />
        <template #content>
          <p class="p-3 text-sm" data-testid="popover-content">Filters live in a popover.</p>
        </template>
      </UPopover>

      <UPopover mode="hover">
        <UButton label="Hover card" color="neutral" variant="ghost" data-testid="open-hovercard" />
        <template #content>
          <p class="p-3 text-sm" data-testid="hovercard-content">A hover card.</p>
        </template>
      </UPopover>

      <UTooltip text="Copies the order id">
        <UButton icon="i-lucide-clipboard" aria-label="Copy order id" color="neutral" variant="ghost" data-testid="tooltip-trigger" />
      </UTooltip>

      <UDropdownMenu :items="menuItems">
        <UButton icon="i-lucide-ellipsis-vertical" aria-label="Row actions" color="neutral" variant="outline" data-testid="open-menu" />
      </UDropdownMenu>

      <UButton label="Show toast" color="neutral" variant="outline" data-testid="show-toast" @click="showToast" />
    </section>

    <p class="text-sm text-muted">Last menu action: <span data-testid="last-action">{{ lastAction }}</span></p>
  </div>
</template>
