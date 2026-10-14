<script setup lang="ts">
import { FwPageHeader } from "@flowcatalyst/ui";
import { CalendarDate } from "@internationalized/date";
import UButton from "@nuxt/ui/components/Button.vue";
import UCheckbox from "@nuxt/ui/components/Checkbox.vue";
import UForm from "@nuxt/ui/components/Form.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import UInputDate from "@nuxt/ui/components/InputDate.vue";
import type { InputDateProps } from "@nuxt/ui/components/InputDate.vue";
import UInputNumber from "@nuxt/ui/components/InputNumber.vue";
import URadioGroup from "@nuxt/ui/components/RadioGroup.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import USelectMenu from "@nuxt/ui/components/SelectMenu.vue";
import USwitch from "@nuxt/ui/components/Switch.vue";
import UTextarea from "@nuxt/ui/components/Textarea.vue";
import type { FormErrorEvent, FormSubmitEvent } from "@nuxt/ui";
import { computed, reactive, ref, shallowRef, useTemplateRef } from "vue";
import { z } from "zod";

// The shape a generated Zod schema (docs/contracts/openapi.md section 11) would have.
const schema = z.object({
  customer: z.string().min(1, "Required").max(80),
  email: z.email("Not an email address"),
  status: z.enum(["OPEN", "PLACED", "CANCELLED"]),
  tags: z.array(z.string()).max(3, "At most three tags"),
  // Form state holds no `undefined` for a v-model-bound field: with exactOptionalPropertyTypes a
  // Nuxt UI input's `modelValue?: string` does not accept one (docs/ui/u0.md). An optional request
  // field is mapped from "" when the request is built.
  notes: z.string().max(500),
  urgent: z.boolean(),
  notify: z.boolean(),
  priority: z.enum(["low", "normal", "high"]),
  quantity: z.number().int().min(1).max(99),
});
type OrderForm = z.output<typeof schema>;

const initial: OrderForm = {
  customer: "Acme",
  email: "orders@acme.example",
  status: "OPEN",
  tags: [],
  notes: "",
  urgent: false,
  notify: true,
  priority: "normal",
  quantity: 1,
};
const state = reactive<OrderForm>({ ...initial, tags: [...initial.tags] });

const statusItems: { label: string; value: OrderForm["status"] }[] = [
  { label: "Open", value: "OPEN" },
  { label: "Placed", value: "PLACED" },
  { label: "Cancelled", value: "CANCELLED" },
];
const tagItems = ["wholesale", "export", "priority", "sample"];
const priorityItems: { label: string; value: OrderForm["priority"] }[] = [
  { label: "Low", value: "low" },
  { label: "Normal", value: "normal" },
  { label: "High", value: "high" },
];

type Period = InputDateProps<true>["modelValue"];
const period = shallowRef<Period>({ start: new CalendarDate(2026, 10, 1), end: new CalendarDate(2026, 10, 14) });
const periodText = computed(() => `${period.value?.start?.toString() ?? ""}..${period.value?.end?.toString() ?? ""}`);

const form = useTemplateRef("form");
// UForm's `dirty` means "a field had input since the last submit", not "differs from the initial
// values" (checked at U0); useDirtyForm (U2) compares a snapshot, as the platform's does.
const formDirty = computed(() => form.value?.dirty === true);
const differsFromInitial = computed(() => JSON.stringify(state) !== JSON.stringify(initial));
const submitted = ref("");

const errorSummary = ref("");

function onSubmit(event: FormSubmitEvent<OrderForm>): void {
  errorSummary.value = "";
  submitted.value = `${event.data.customer} x${String(event.data.quantity)}`;
}

function onError(event: FormErrorEvent): void {
  errorSummary.value = event.errors.map((error) => `${error.name ?? "?"}: ${error.message}`).join("; ");
}
</script>

<template>
  <div class="space-y-6">
    <FwPageHeader title="Forms" subtitle="UForm with a Zod 4 schema and every input the kit maps" />

    <UForm ref="form" :schema="schema" :state="state" class="grid max-w-xl gap-4" novalidate data-testid="order-form" @submit="onSubmit" @error="onError">
      <UFormField label="Customer" name="customer" required>
        <UInput v-model="state.customer" class="w-full" data-testid="customer" />
      </UFormField>
      <UFormField label="Email" name="email" required>
        <UInput v-model="state.email" type="email" class="w-full" data-testid="email" />
      </UFormField>
      <UFormField label="Status" name="status">
        <USelect v-model="state.status" :items="statusItems" class="w-full" data-testid="status" />
      </UFormField>
      <UFormField label="Tags" name="tags" description="Up to three">
        <USelectMenu v-model="state.tags" :items="tagItems" multiple class="w-full" data-testid="tags" />
      </UFormField>
      <UFormField label="Notes" name="notes">
        <UTextarea v-model="state.notes" class="w-full" />
      </UFormField>
      <UFormField label="Quantity" name="quantity">
        <UInputNumber v-model="state.quantity" :min="1" :max="99" />
      </UFormField>
      <UFormField label="Priority" name="priority">
        <URadioGroup v-model="state.priority" :items="priorityItems" orientation="horizontal" />
      </UFormField>
      <UFormField name="urgent">
        <!-- UCheckbox emits boolean | "indeterminate"; the form keeps a boolean. -->
        <UCheckbox :model-value="state.urgent" label="Urgent" @update:model-value="(value) => (state.urgent = value === true)" />
      </UFormField>
      <UFormField name="notify">
        <USwitch v-model="state.notify" label="Notify the customer" />
      </UFormField>
      <div>
        <UButton type="submit" label="Save order" data-testid="submit" />
      </div>
    </UForm>

    <dl class="grid max-w-xl grid-cols-2 gap-1 text-sm">
      <dt class="text-muted">UForm dirty</dt>
      <dd data-testid="form-dirty">{{ formDirty }}</dd>
      <dt class="text-muted">Differs from initial values</dt>
      <dd data-testid="differs">{{ differsFromInitial }}</dd>
      <dt class="text-muted">Errors</dt>
      <dd data-testid="errors">{{ errorSummary }}</dd>
      <dt class="text-muted">Submitted</dt>
      <dd data-testid="submitted">{{ submitted }}</dd>
    </dl>

    <section class="max-w-xl space-y-2">
      <h2 class="font-semibold">Date range</h2>
      <UFormField label="Period" name="period">
        <UInputDate v-model="period" range data-testid="period" />
      </UFormField>
      <p class="text-sm text-muted">Selected: <span data-testid="period-text">{{ periodText }}</span></p>
    </section>
  </div>
</template>
