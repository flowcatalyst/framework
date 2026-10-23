<script setup lang="ts">
// A revealed secret, shown to the person who asked for it. The value lives only in the `value` prop of
// the page that received it (never in the URL, in storage, in a log or in a list), it is read-only text
// here with a Copy button, and closing the dialog drops it: the page sets `value` back to undefined and
// the text leaves the document.
import { useFlash } from "../../composables/useFlash.ts";
import UButton from "@nuxt/ui/components/Button.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import UModal from "@nuxt/ui/components/Modal.vue";
import { computed } from "vue";

const { value, label } = defineProps<{
  /** The plaintext, or undefined while there is none to show. */
  value: string | undefined;
  /** What it is the value of (`ftp/password for clt_0abc`). */
  label: string;
}>();

const emit = defineEmits<{
  close: [];
}>();

const flash = useFlash();
const open = computed(() => value !== undefined);

async function copy(): Promise<void> {
  try {
    await navigator.clipboard.writeText(value ?? "");
    flash.success("Value copied.");
  } catch {
    flash.error("The value could not be copied; select it and copy it by hand.");
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
    title="Secret value"
    :description="`The value of ${label}. It is shown once: close this window and it is gone.`"
    :dismissible="false"
    :close="false"
    @update:open="onUpdate"
  >
    <template #body>
      <div class="space-y-3" data-testid="reveal-dialog">
        <label class="block text-[13px] font-medium text-toned" for="reveal-value-text">Value of {{ label }}</label>
        <UInput id="reveal-value-text" :model-value="value ?? ''" readonly autocomplete="off" class="w-full" data-testid="reveal-value" />
      </div>
    </template>
    <template #footer>
      <div class="flex w-full items-center justify-end gap-2">
        <UButton label="Copy" icon="i-lucide-copy" color="neutral" variant="outline" data-testid="reveal-copy" @click="copy" />
        <UButton label="Close" data-testid="reveal-close" @click="emit('close')" />
      </div>
    </template>
  </UModal>
</template>
