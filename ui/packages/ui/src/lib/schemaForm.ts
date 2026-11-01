// What FwFormDialog and FwFormPage share (ui-kit form-dialog.md): a UForm validated by the
// operation's generated Zod schema and submitted through a function the page passes. While the
// submit runs the form is busy (no double submit). A failed submit puts the 400's field errors on
// the fields and anything else in the error region, then focuses the first invalid field. "Changed"
// compares the state with the values the form started with, and also what the fields show with what
// they showed then: a field that commits to the state on blur (UInputNumber) counts as changed while
// it is typed in (friction F21).
import type { ServiceError } from "@flowcatalyst/api-runtime";
import { applyServiceError } from "@flowcatalyst/api-runtime";
import type { FormSubmitEvent } from "@nuxt/ui";
import { computed, nextTick, shallowRef, type ComputedRef, type ShallowRef } from "vue";
import { useDirtyForm } from "../composables/useDirtyForm.ts";

/** What the form uses of UForm's exposed API. */
export interface FormApi {
  submit(): Promise<void>;
  setErrors(errors: { name?: string; message: string }[]): void;
  getErrors(): readonly { readonly name?: string | undefined }[];
}

export interface SchemaFormOptions<O> {
  /** The form's values, owned by the page. */
  readonly state: () => unknown;
  /** Sends the parsed values; resolves undefined on success or the call's ServiceError. */
  readonly submit: (data: O) => Promise<ServiceError | undefined>;
  /** The UForm (`useTemplateRef`). */
  readonly form: Readonly<ShallowRef<FormApi | null>>;
  /** The element around the fields (`useTemplateRef`). */
  readonly fields: Readonly<ShallowRef<HTMLElement | null>>;
  /** Whether the form is still shown, when the snapshot of the fields is taken after the render. */
  readonly shown: () => boolean;
  /** The submit succeeded; the form is clean again. */
  readonly saved: (data: O) => void;
}

export interface SchemaForm<O> {
  readonly busy: Readonly<ShallowRef<boolean>>;
  readonly formError: Readonly<ShallowRef<string | undefined>>;
  /** The values or the fields differ from the ones the form started with. */
  readonly changed: ComputedRef<boolean>;
  /** The form is shown with its values: takes the snapshots. */
  readonly start: () => void;
  /** The form is gone: forgets the snapshots. */
  readonly stop: () => void;
  /** Focuses the first element matching, once the render and UForm are done. */
  readonly focusFirst: (selector: string) => void;
  /** On the fields' `input` and `change`. */
  readonly onFieldInput: () => void;
  /** UForm's `submit`. */
  readonly onSubmit: (event: FormSubmitEvent<O>) => Promise<void>;
  /** UForm's `error`. */
  readonly onError: () => void;
  /** The submit button's click. */
  readonly submitForm: () => Promise<void>;
  /** Bound on the submit button: the field keeps focus through the click. */
  readonly keepFocus: { onMousedown: (event: MouseEvent) => void };
}

/** The first field a form focuses. */
export const FIRST_FIELD = "input:not([type=hidden]), textarea, select, button[role=combobox], [role=radio], [role=checkbox]";

export function useSchemaForm<O>(options: SchemaFormOptions<O>): SchemaForm<O> {
  const { fields, form } = options;
  const busy = shallowRef(false);
  const formError = shallowRef<string | undefined>(undefined);
  const dirtyForm = useDirtyForm(options.state);
  // The fields' drafts: what the inputs show, which runs ahead of the state for a field that commits on blur.
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

  function start(): void {
    formError.value = undefined;
    busy.value = false;
    dirtyForm.markClean();
    draftBaseline.value = undefined;
    // The fields are in the DOM once rendered: a macrotask, as focusFirst.
    setTimeout(() => {
      if (options.shown()) {
        draftBaseline.value = drafts();
      }
    }, 0);
  }

  function stop(): void {
    dirtyForm.reset();
    draftBaseline.value = undefined;
  }

  // After the render and after UForm re-enables its fields (it disables them while submitting and
  // only lets go once the submit handler returned): a macrotask, not just the next tick.
  function focusFirst(selector: string): void {
    setTimeout(() => {
      fields.value?.querySelector<HTMLElement>(selector)?.focus();
    }, 0);
  }

  async function onSubmit(event: FormSubmitEvent<O>): Promise<void> {
    busy.value = true;
    formError.value = undefined;
    try {
      const error = await options.submit(event.data);
      if (error === undefined) {
        dirtyForm.markClean();
        draftBaseline.value = drafts();
        options.saved(event.data);
        return;
      }
      const applied = applyServiceError(form.value, error);
      formError.value = applied.message;
      focusFirst("[aria-invalid=true]");
    } finally {
      busy.value = false;
    }
  }

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

  return {
    busy,
    formError,
    changed,
    start,
    stop,
    focusFirst,
    onFieldInput: () => {
      draftTick.value += 1;
    },
    onSubmit,
    onError: () => {
      focusFirst("[aria-invalid=true]");
    },
    submitForm,
    // UButton declares no mousedown event, so the listener goes through v-bind as an attribute.
    keepFocus: {
      onMousedown: (event: MouseEvent) => {
        event.preventDefault();
      },
    },
  };
}
