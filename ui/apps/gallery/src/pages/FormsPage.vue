<script setup lang="ts">
import { useDirtyForm } from "@flowcatalyst/ui";
import { CalendarDate } from "@internationalized/date";
import UButton from "@nuxt/ui/components/Button.vue";
import UCheckbox from "@nuxt/ui/components/Checkbox.vue";
import UCheckboxGroup from "@nuxt/ui/components/CheckboxGroup.vue";
import UForm from "@nuxt/ui/components/Form.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import UInputDate from "@nuxt/ui/components/InputDate.vue";
import type { InputDateProps } from "@nuxt/ui/components/InputDate.vue";
import UInputMenu from "@nuxt/ui/components/InputMenu.vue";
import UInputNumber from "@nuxt/ui/components/InputNumber.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import URadioGroup from "@nuxt/ui/components/RadioGroup.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import USelectMenu from "@nuxt/ui/components/SelectMenu.vue";
import USwitch from "@nuxt/ui/components/Switch.vue";
import UTextarea from "@nuxt/ui/components/Textarea.vue";
import type { FormErrorEvent, FormSubmitEvent } from "@nuxt/ui";
import { computed, reactive, ref, shallowRef, useTemplateRef } from "vue";
import { z } from "zod";
import GallerySection from "../components/GallerySection.vue";

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
// values" (checked at U0); useDirtyForm compares a snapshot, as the platform's does.
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

// filter-select: a status filter with a clear button ("any status").
const statusFilter = shallowRef<OrderForm["status"] | null>(null);
// autocomplete: server-searched in an application (local filtering off); here a fixed list.
const customers = ["Acme", "Globex", "Initech", "Umbrella", "Hooli"];
const customer = shallowRef("");
const channels = shallowRef<string[]>(["email"]);
const search = shallowRef("");

// dirty-form: Save enabled only when the values differ from the loaded ones.
const profile = reactive({ name: "Ada Lovelace", team: "Analytics" });
const dirtyForm = useDirtyForm(() => profile);
dirtyForm.markClean();
const saved = shallowRef(0);
function saveProfile(): void {
  saved.value += 1;
  dirtyForm.markClean();
}
function discardProfile(): void {
  Object.assign(profile, { name: "Ada Lovelace", team: "Analytics" });
}
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="Forms" description="Nuxt UI's form, fields and inputs used directly, with a Zod 4 schema" />

    <GallerySection :behaviours="['form-field-errors', 'select-keyboard']" title="A form (UForm)" description="UForm, a Zod schema, UFormField (label, hint, description, help, required marker, error) around each input.">
      <UForm ref="form" :schema="schema" :state="state" class="grid max-w-xl gap-4" novalidate data-testid="order-form" @submit="onSubmit" @error="onError">
        <UFormField label="Customer" name="customer" required>
          <UInput v-model="state.customer" class="w-full" data-testid="customer" />
        </UFormField>
        <UFormField label="Email" name="email" required hint="Order confirmations">
          <UInput v-model="state.email" type="email" class="w-full" data-testid="email" />
        </UFormField>
        <UFormField label="Status" name="status">
          <USelect v-model="state.status" :items="statusItems" class="w-full" data-testid="status" />
        </UFormField>
        <UFormField label="Tags" name="tags" description="Up to three">
          <USelectMenu v-model="state.tags" :items="tagItems" multiple class="w-full" data-testid="tags" />
        </UFormField>
        <UFormField label="Notes" name="notes" help="Shown to the warehouse.">
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
    </GallerySection>

    <GallerySection :behaviours="['select-keyboard']" title="Filter select (USelectMenu)" description="A select with a clear button for 'any'.">
      <USelectMenu v-model="statusFilter" :items="statusItems" value-key="value" placeholder="Any status" clear class="w-56" aria-label="Status filter" data-testid="status-filter" />
      <p class="text-sm text-muted">Filter: <span data-testid="status-filter-value">{{ statusFilter ?? "any" }}</span></p>
    </GallerySection>

    <GallerySection :behaviours="['autocomplete']" title="Autocomplete (UInputMenu)" description="Type to narrow, arrow keys to choose.">
      <UFormField label="Customer">
        <UInputMenu v-model="customer" :items="customers" placeholder="Start typing" class="w-56" data-testid="customer-autocomplete" />
      </UFormField>
      <p class="text-sm text-muted">Chosen: <span data-testid="customer-chosen">{{ customer }}</span></p>
    </GallerySection>

    <GallerySection title="Search input (UInput)" description="A search box (the table toolbar's; debounced by useListState).">
      <UInput v-model="search" type="search" icon="i-lucide-search" placeholder="Search orders…" aria-label="Search orders" class="w-64" />
    </GallerySection>

    <GallerySection title="Checkbox group (UCheckboxGroup)">
      <UCheckboxGroup v-model="channels" legend="Notify by" :items="['email', 'sms', 'webhook']" />
    </GallerySection>

    <GallerySection :behaviours="['date-range']" title="Date range (UInputDate)" description="Range mode, edited segment by segment by keyboard.">
      <UFormField label="Period" name="period">
        <UInputDate v-model="period" range data-testid="period" />
      </UFormField>
      <p class="text-sm text-muted">Selected: <span data-testid="period-text">{{ periodText }}</span></p>
    </GallerySection>

    <GallerySection :behaviours="['dirty-snapshot']" title="Dirty form (useDirtyForm)" description="Save stays disabled until a value differs from the loaded one; typing it back disables it again; Discard restores. UForm's own `dirty` means 'had input', so it is not used for this.">
      <form class="grid max-w-md gap-3" data-testid="profile-form" @submit.prevent="saveProfile">
        <UFormField label="Name"><UInput v-model="profile.name" class="w-full" data-testid="profile-name" /></UFormField>
        <UFormField label="Team"><UInput v-model="profile.team" class="w-full" /></UFormField>
        <div class="flex gap-2">
          <UButton type="submit" label="Save" :disabled="!dirtyForm.dirty.value" data-testid="profile-save" />
          <UButton v-if="dirtyForm.dirty.value" label="Discard" color="neutral" variant="ghost" @click="discardProfile" />
        </div>
        <p class="text-sm text-muted">Saved <span data-testid="profile-saved">{{ saved }}</span> times.</p>
      </form>
    </GallerySection>
  </div>
</template>
