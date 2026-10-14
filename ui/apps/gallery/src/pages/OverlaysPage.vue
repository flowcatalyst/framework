<script setup lang="ts">
import { FwAlertDialog, FwPageHeader, useConfirm } from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UModal from "@nuxt/ui/components/Modal.vue";
import UPopover from "@nuxt/ui/components/Popover.vue";
import USlideover from "@nuxt/ui/components/Slideover.vue";
import { shallowRef } from "vue";
import GallerySection from "../components/GallerySection.vue";

const alertOpen = shallowRef(false);
const busy = shallowRef(false);
const alertResult = shallowRef("none");
const confirmResult = shallowRef("none");
const confirm = useConfirm();

async function revoke(): Promise<void> {
  busy.value = true;
  await new Promise((resolve) => setTimeout(resolve, 400));
  busy.value = false;
  alertOpen.value = false;
  alertResult.value = "revoked";
}

async function askCancel(tone: "danger" | "warn" | "primary"): Promise<void> {
  const confirmed = await confirm({
    title: "Cancel order 1042?",
    message: "The customer is told at once. A cancelled order cannot be reopened.",
    confirmLabel: "Cancel order",
    cancelLabel: "Keep order",
    tone,
  });
  confirmResult.value = confirmed ? `confirmed (${tone})` : `cancelled (${tone})`;
}
</script>

<template>
  <div class="space-y-6">
    <FwPageHeader title="Overlays" subtitle="Dialogs, sheets, popovers, the alert dialog and confirmations" />

    <GallerySection spec="dialog" title="Dialog" description="A modal: focus trap, Escape closes, focus returns to the button.">
      <UModal title="Discard changes?" description="Your edits to this order are not saved.">
        <UButton label="Open modal" color="neutral" variant="outline" data-testid="open-modal" />
        <template #body>
          <p class="text-sm">The modal traps focus and closes with Escape.</p>
        </template>
        <template #footer="{ close }">
          <UButton label="Keep editing" color="neutral" variant="outline" data-testid="modal-close" @click="close" />
        </template>
      </UModal>
    </GallerySection>

    <GallerySection spec="sheet" title="Sheet" description="A modal slideover (the record drawer, FwRecordDrawer, is the non-modal one: see Orders).">
      <USlideover title="Order 1042" description="A modal sheet on the right.">
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
    </GallerySection>

    <GallerySection spec="hover-card" title="Popover and hover card">
      <div class="flex gap-3">
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
      </div>
    </GallerySection>

    <GallerySection spec="alert-dialog" title="Alert dialog (FwAlertDialog)" description="role=alertdialog; Cancel is focused; Escape cancels; the mask does not dismiss; the confirmed action can keep it open while it runs.">
      <div class="flex items-center gap-3">
        <FwAlertDialog
          v-model:open="alertOpen"
          title="Revoke the API key?"
          description="Applications using it stop working at once."
          confirm-label="Revoke key"
          tone="danger"
          :busy="busy"
          @confirm="revoke"
          @cancel="alertResult = 'cancelled'"
        >
          <template #trigger>
            <UButton label="Revoke key…" color="error" variant="outline" data-testid="open-alert" />
          </template>
        </FwAlertDialog>
        <span class="text-sm text-muted">Result: <span data-testid="alert-result">{{ alertResult }}</span></span>
      </div>
    </GallerySection>

    <GallerySection spec="confirm-dialog" title="Confirm dialog (useConfirm)" description="A promise: true when confirmed. The three tones.">
      <div class="flex flex-wrap items-center gap-3">
        <UButton label="Danger" color="neutral" variant="outline" data-testid="confirm-danger" @click="askCancel('danger')" />
        <UButton label="Warn" color="neutral" variant="outline" data-testid="confirm-warn" @click="askCancel('warn')" />
        <UButton label="Default" color="neutral" variant="outline" data-testid="confirm-primary" @click="askCancel('primary')" />
        <span class="text-sm text-muted">Result: <span data-testid="confirm-result">{{ confirmResult }}</span></span>
      </div>
    </GallerySection>
  </div>
</template>
