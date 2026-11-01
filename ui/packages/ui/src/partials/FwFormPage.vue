<script setup lang="ts" generic="O extends object, I extends object">
// A create or edit form as a page of its own, for a form too long for FwFormDialog: its own route
// (a link, Back leaves it), the page's title block, the fields in a readable column with an optional
// aside beside them on a wide screen (help, a connection test), and Cancel and Submit in a bar that
// stays in view at the bottom. The form behaves as FwFormDialog's (lib/schemaForm.ts): the
// operation's generated Zod schema, busy while submitting, a 400's field errors on their fields and
// focus on the first invalid one, `requireChange` for edit forms. Leaving a changed form (Cancel, a
// link, Back) asks "Discard changes?" first, and closing or reloading the tab gets the browser's own
// question; after a successful submit it leaves without asking. The page owns the call and where to
// go after `saved` (the new record's drawer, usually).
//
// An edit page loads its record first: `loading` shows that instead of the form, and the form's
// starting values are the state as it is when `loading` turns false; `error` says why it could not.
import type { ServiceError } from "@flowcatalyst/api-runtime";
import UButton from "@nuxt/ui/components/Button.vue";
import UForm from "@nuxt/ui/components/Form.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import UProgress from "@nuxt/ui/components/Progress.vue";
import { useEventListener } from "@vueuse/core";
import { onBeforeUnmount, onMounted, useId, useTemplateRef, watch } from "vue";
import { onBeforeRouteLeave, useRouter, type RouteLocationRaw } from "vue-router";
import type { z } from "zod";
import { confirmDiscardChanges, useConfirm } from "../composables/useConfirm.ts";
import { FIRST_FIELD, useSchemaForm, type FormApi } from "../lib/schemaForm.ts";

const {
  schema,
  state,
  title,
  description,
  submitLabel,
  requireChange = false,
  submit,
  back,
  backLabel = "Back",
  loading = false,
  error,
} = defineProps<{
  /** The request body's generated Zod schema (zod.gen.ts): output O, input I. */
  schema: z.ZodType<O, I>;
  /** The form's values, owned by the page (`reactive({...})`); edited in place by the fields. */
  state: Partial<I>;
  /** The page's h1. */
  title: string;
  description?: string;
  submitLabel: string;
  /** Edit forms: Submit stays disabled until a value differs from the values the form started with. */
  requireChange?: boolean;
  /** Sends the parsed values; resolves undefined on success or the call's ServiceError. */
  submit: (data: O) => Promise<ServiceError | undefined>;
  /** Where Cancel and the back link go: the list, or the record's drawer. */
  back: RouteLocationRaw;
  /** The back link's text ("Ingestions"). */
  backLabel?: string;
  /** The record is being loaded: no form yet. */
  loading?: boolean;
  /** Why the record could not be loaded: no form. */
  error?: string | undefined;
}>();

const emit = defineEmits<{
  /** The submit succeeded; the form is clean, so the page can navigate without a question. */
  saved: [data: O];
}>();

const slots = defineSlots<{
  /** The fields (FwFormSection with `level` 2, UFormField + inputs bound to `state`). */
  default?: () => unknown;
  /** Beside the fields on a wide screen, under them on a narrow one. */
  aside?: () => unknown;
}>();

const id = useId();
const router = useRouter();
const confirm = useConfirm();
const form = useTemplateRef<FormApi>("form");
const fields = useTemplateRef<HTMLElement>("fields");
let shown = false;
const { busy, formError, changed, start, stop, focusFirst, onFieldInput, onSubmit, onError, submitForm, keepFocus } = useSchemaForm<O>({
  state: () => state,
  submit: (data) => submit(data),
  form,
  fields,
  shown: () => shown,
  saved: (data) => {
    emit("saved", data);
  },
});

// After the render (flush "post", started once mounted): the fields' snapshot is of the fields shown.
onMounted(() => {
  watch(
    () => loading || error !== undefined,
    (waiting) => {
      shown = !waiting;
      if (waiting) {
        stop();
        return;
      }
      start();
      focusFirst(FIRST_FIELD);
    },
    { immediate: true, flush: "post" },
  );
});
onBeforeUnmount(() => {
  shown = false;
});

onBeforeRouteLeave(() => (changed.value ? confirmDiscardChanges(confirm) : true));
useEventListener(window, "beforeunload", (event: BeforeUnloadEvent) => {
  if (changed.value) {
    event.preventDefault();
  }
});

function cancel(): void {
  if (!busy.value) {
    void router.push(back);
  }
}
</script>

<template>
  <div data-testid="fw-form-page">
    <UButton :to="back" :label="backLabel" icon="i-lucide-arrow-left" color="neutral" variant="link" size="sm" class="-ms-1.5 mt-2" data-testid="fw-form-back" />
    <UPageHeader :title="title" v-bind="description === undefined ? {} : { description }" class="pt-2" />
    <div v-if="loading" role="status" class="py-6" data-testid="fw-form-loading">
      <UProgress animation="carousel" aria-label="Loading" />
      <span class="sr-only">Loading…</span>
    </div>
    <p v-else-if="error !== undefined" role="alert" class="mt-6 flex items-start gap-2 rounded-[2px] border border-error px-3 py-2 text-sm text-error" data-testid="fw-form-load-error">
      {{ error }}
    </p>
    <div v-else :class="slots.aside === undefined ? 'max-w-3xl' : 'grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]'" class="mt-6">
      <UForm
        :id="`${id}-form`"
        ref="form"
        :schema="schema"
        :state="state"
        :aria-busy="busy"
        novalidate
        class="flex min-w-0 flex-col gap-4"
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
        <!-- The shell's panel scrolls inside its padding (p-4, sm:p-6): the bar sticks to the panel's
             bottom edge, not the padding's, so nothing shows beneath it. -->
        <div class="sticky -bottom-4 z-10 flex items-center justify-end gap-2 border-t border-default bg-default py-3 sm:-bottom-6" data-testid="fw-form-actions">
          <!-- mousedown is prevented on both so the field keeps focus: no blur validation reflows the
               form between mousedown and click (the "lost submit" of docs/ui/u0.md). -->
          <UButton label="Cancel" color="neutral" variant="ghost" :disabled="busy" data-testid="fw-form-cancel" v-bind="keepFocus" @click="cancel" />
          <UButton
            :label="submitLabel"
            :loading="busy"
            :disabled="busy || (requireChange && !changed)"
            data-testid="fw-form-submit"
            v-bind="keepFocus"
            @click="submitForm"
          />
        </div>
      </UForm>
      <aside v-if="slots.aside !== undefined" class="min-w-0 lg:sticky lg:top-4 lg:self-start" data-testid="fw-form-aside">
        <slot name="aside" />
      </aside>
    </div>
  </div>
</template>
