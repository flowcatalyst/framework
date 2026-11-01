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
// the dialog also compares the fields' own drafts with the ones it opened with (friction F21). The
// form's behaviour is lib/schemaForm.ts, shared with FwFormPage.
import type { ServiceError } from "@flowcatalyst/api-runtime";
import UButton from "@nuxt/ui/components/Button.vue";
import UForm from "@nuxt/ui/components/Form.vue";
import UModal from "@nuxt/ui/components/Modal.vue";
import { shallowRef, useId, useTemplateRef, watch } from "vue";
import type { z } from "zod";
import FwAlertDialog from "../components/FwAlertDialog.vue";
import { FIRST_FIELD, useSchemaForm, type FormApi } from "../lib/schemaForm.ts";

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
const form = useTemplateRef<FormApi>("form");
const fields = useTemplateRef<HTMLElement>("fields");
const askDiscard = shallowRef(false);
const { busy, formError, changed, start, stop, focusFirst, onFieldInput, onSubmit, onError, submitForm, keepFocus } = useSchemaForm<O>({
  state: () => state,
  submit: (data) => submit(data),
  form,
  fields,
  shown: () => open.value,
  saved: (data) => {
    open.value = false;
    emit("saved", data);
  },
});

watch(
  open,
  (isOpen) => {
    if (isOpen) {
      start();
    } else {
      stop();
    }
  },
  { immediate: true },
);

const content = {
  // Initial focus: the first field, not the header's close button (form-dialog.md).
  onOpenAutoFocus: (event: Event) => {
    event.preventDefault();
    focusFirst(FIRST_FIELD);
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
