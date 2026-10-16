<script setup lang="ts">
// An invitation link, shown once, to the person who asked for it. The link is a credential: it lives
// only in the `link` prop of the page that received it (never in the URL, in storage, in a log or in a
// list), it is read-only text in this dialog with a Copy button, and closing the dialog drops it: the
// page sets `link` back to undefined and the text leaves the document.
import { useFlash } from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import UModal from "@nuxt/ui/components/Modal.vue";
import { computed } from "vue";

const { link, who, expiresAt } = defineProps<{
  /** The link, or undefined while there is none to show. */
  link: string | undefined;
  /** Whom the link is for (a name or an email). */
  who: string;
  /** When the link lapses, if the platform said. */
  expiresAt?: string | undefined;
}>();

const emit = defineEmits<{
  close: [];
}>();

const flash = useFlash();
const open = computed(() => link !== undefined);

async function copy(): Promise<void> {
  try {
    await navigator.clipboard.writeText(link ?? "");
    flash.success("Link copied.");
  } catch {
    flash.error("The link could not be copied; select it and copy it by hand.");
  }
}

function onUpdate(next: boolean): void {
  if (!next) {
    emit("close");
  }
}
</script>

<template>
  <UModal
    :open="open"
    title="Invitation link"
    :description="`Give this link to ${who}. It is shown once: close this window and it is gone. Anyone who has it can set the password.`"
    :dismissible="false"
    :close="false"
    @update:open="onUpdate"
  >
    <template #body>
      <div class="space-y-3" data-testid="invite-link-dialog">
        <label class="block text-[13px] font-medium text-toned" for="invite-link-text">Link for {{ who }}</label>
        <UInput id="invite-link-text" :model-value="link ?? ''" readonly autocomplete="off" class="w-full" data-testid="invite-link-text" />
        <p v-if="expiresAt !== undefined" class="text-sm text-muted">It stops working at {{ expiresAt }}.</p>
        <p class="text-sm text-muted">The earlier link, if there was one, no longer works.</p>
      </div>
    </template>
    <template #footer>
      <div class="flex w-full items-center justify-end gap-2">
        <UButton label="Copy link" icon="i-lucide-copy" color="neutral" variant="outline" data-testid="invite-link-copy" @click="copy" />
        <UButton label="Done" data-testid="invite-link-done" @click="emit('close')" />
      </div>
    </template>
  </UModal>
</template>
