<script setup lang="ts" generic="O extends object, I extends object">
// ui-kit form-dialog.md: every create and edit form as a modal holding a UForm validated by the
// operation's generated Zod schema (the browser checks what the server will check), submitted through
// a function the page passes (the partial never calls the API itself). While the submit runs the form
// is busy (aria-busy, both buttons disabled: no double submit). A failed submit puts the 400's field
// errors on the fields and anything else (a business rule, a field the form does not show) in the
// error region, then focuses the first invalid field. Closing a changed form (Cancel, X, Escape, a
// click outside) asks "Discard changes?" first; Keep editing is the focused answer. Edit forms
// (`requireChange`) enable Save only once a value changed (a value typed back counts as unchanged).
// A field that commits to the state on blur (UInputNumber) counts as changed while it is typed in:
// the dialog also compares the fields' own drafts with the ones it opened with (friction F21).
import type { ServiceError } from "@flowcatalyst/api-runtime";
import { applyServiceError } from "@flowcatalyst/api-runtime";
import type { FormSubmitEvent } from "@nuxt/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UForm from "@nuxt/ui/components/Form.vue";
import UModal from "@nuxt/ui/components/Modal.vue";
import { computed, nextTick, shallowRef, useId, useTemplateRef, watch } from "vue";
import type { z } from "zod";
import FwAlertDialog from "../components/FwAlertDialog.vue";
import { useDirtyForm } from "../composables/useDirtyForm.ts";

const {
  schema,
  state,
  title,
  description,
  submitLabel,
  requireChange = false,
  submit,
} = defineProps<{
  /** The request body's generated Zod schema (zod.gen.ts): output O, input I. */
  schema: z.ZodType<O, I>;
  /** The form's values, owned by the page (`reactive({...})`); edited in place by the fields. */
  state: Partial<I>;
  title: string;
  description?: string;
  submitLabel: string;
  /** Edit forms: Save stays disabled until a value differs from the values the dialog opened with. */
  requireChange?: boolean;
  /** Sends the parsed values; resolves undefined on success or the call's ServiceError. */
  submit: (data: O) => Promise<ServiceError | undefined>;
}>();

const open = defineModel<boolean>("open", { required: true });

const emit = defineEmits<{
  /** The submit succeeded and the dialog closed. */
  saved: [data: O];
}>();

defineSlots<{
  /** The fields (UFormField + inputs bound to `state`). */
  default?: () => unknown;
}>();

const id = useId();
/** What this dialog uses of UForm's exposed API. */
interface FormApi {
  submit(): Promise<void>;
  setErrors(errors: { name?: string; message: string }[]): void;
  getErrors(): readonly { readonly name?: string | undefined }[];
}

const form = useTemplateRef<FormApi>("form");
const fields = useTemplateRef<HTMLElement>("fields");
const busy = shallowRef(false);
const formError = shallowRef<string | undefined>(undefined);
const askDiscard = shallowRef(false);
const dirtyForm = useDirtyForm(() => state);
// The fields' drafts: what the inputs show, which runs ahead of `state` for a field that commits on blur.
const draftBaseline = shallowRef<string | undefined>(undefined);
const draftTick = shallowRef(0);
const draftChanged = computed(() => {
  const tick = draftTick.value; // re-evaluate after every input event
  return tick >= 0 && draftBaseline.value !== undefined && drafts() !== draftBaseline.value;
});
const changed = computed(() => dirtyForm.dirty.value || draftChanged.value);

function drafts(): string {
  const inputs = fields.value?.querySelectorAll<HTMLInputElement>("input:not([type=hidden]), textarea") ?? [];
  return JSON.stringify(Array.from(inputs, (input) => (input.type === "checkbox" || input.type === "radio" ? input.checked : input.value)));
}

function onFieldInput(): void {
  draftTick.value += 1;
}

watch(
  open,
  (isOpen) => {
    if (isOpen) {
      formError.value = undefined;
      busy.value = false;
      dirtyForm.markClean();
      draftBaseline.value = undefined;
      // The fields are in the DOM once the modal rendered: a macrotask, as focusFirst.
      setTimeout(() => {
        if (open.value) {
          draftBaseline.value = drafts();
        }
      }, 0);
    } else {
      dirtyForm.reset();
      draftBaseline.value = undefined;
    }
  },
  { immediate: true },
);

// After the render and after UForm re-enables its fields (it disables them while submitting and
// only lets go once the submit handler returned): a macrotask, not just the next tick.
function focusFirst(selector: string): void {
  setTimeout(() => {
    fields.value?.querySelector<HTMLElement>(selector)?.focus();
  }, 0);
}

const content = {
  // Initial focus: the first field, not the header's close button (form-dialog.md).
  onOpenAutoFocus: (event: Event) => {
    event.preventDefault();
    focusFirst("input:not([type=hidden]), textarea, select, button[role=combobox], [role=radio], [role=checkbox]");
  },
};

function requestClose(): void {
  if (busy.value) {
    return;
  }
  if (changed.value) {
    askDiscard.value = true;
  } else {
    open.value = false;
  }
}

function discard(): void {
  askDiscard.value = false;
  open.value = false;
}

async function onSubmit(event: FormSubmitEvent<O>): Promise<void> {
  busy.value = true;
  formError.value = undefined;
  try {
    const error = await submit(event.data);
    if (error === undefined) {
      dirtyForm.markClean();
      draftBaseline.value = drafts();
      open.value = false;
      emit("saved", event.data);
      return;
    }
    const applied = applyServiceError(form.value, error);
    formError.value = applied.message;
    focusFirst("[aria-invalid=true]");
  } finally {
    busy.value = false;
  }
}

function onError(): void {
  focusFirst("[aria-invalid=true]");
}

// UButton declares no mousedown event, so the listener goes through v-bind as an attribute.
const keepFocus = {
  onMousedown: (event: MouseEvent) => {
    event.preventDefault();
  },
};

async function submitForm(): Promise<void> {
  // The field keeps focus through the click (keepFocus); blur it now so a field that commits on
  // blur (UInputNumber: Reka's NumberField parses the typed text on blur or Enter) has its value
  // in the state before UForm reads it. The click itself has already landed.
  const active = document.activeElement;
  if (active instanceof HTMLElement && fields.value?.contains(active) === true) {
    active.blur();
    await nextTick();
  }
  await form.value?.submit();
}
</script>

<template>
  <UModal
    :open="open"
    :title="title"
    :description="description ?? ''"
    :dismissible="false"
    :close="false"
    :content="content"
    @close:prevent="requestClose"
  >
    <template #actions>
      <UButton icon="i-lucide-x" color="neutral" variant="ghost" square aria-label="Close" class="ms-auto" :disabled="busy" @click="requestClose" />
    </template>
    <template #body>
      <UForm
        :id="`${id}-form`"
        ref="form"
        :schema="schema"
        :state="state"
        :aria-busy="busy"
        novalidate
        class="flex flex-col gap-4"
        @submit="onSubmit"
        @error="onError"
      >
        <div role="alert" data-testid="fw-form-error">
          <p v-if="formError !== undefined" class="flex items-start gap-2 rounded-[2px] border border-error px-3 py-2 text-sm text-error">
            {{ formError }}
          </p>
        </div>
        <div ref="fields" class="flex flex-col gap-4" @input="onFieldInput" @change="onFieldInput">
          <slot />
        </div>
      </UForm>
    </template>
    <template #footer>
      <div class="flex w-full items-center justify-end gap-2">
        <UButton label="Cancel" color="neutral" variant="ghost" :disabled="busy" data-testid="fw-form-cancel" @click="requestClose" />
        <!-- mousedown is prevented so the field keeps focus: no blur validation reflows the dialog
             between mousedown and click (the "lost submit" of docs/ui/u0.md). -->
        <UButton
          :label="submitLabel"
          :loading="busy"
          :disabled="busy || (requireChange && !changed)"
          data-testid="fw-form-submit"
          v-bind="keepFocus"
          @click="submitForm"
        />
      </div>
    </template>
  </UModal>
  <FwAlertDialog
    v-model:open="askDiscard"
    title="Discard changes?"
    description="You have unsaved changes. Discard them?"
    confirm-label="Discard"
    cancel-label="Keep editing"
    color="error"
    @confirm="discard"
  />
</template>
