<script setup lang="ts">
// Add or change one platform config property (platform config contract section 5): the section, the
// property, the optional client and the value in FwFormDialog. A secret's value goes in a password field
// that is never filled in, not even when a secret is edited (the platform never returns it): changing a
// secret needs a new value. Closing the dialog empties the draft, so a typed value does not outlive it.
import type { ServiceError } from "@flowcatalyst/api-runtime";
import FwFormDialog from "../../partials/FwFormDialog.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import USwitch from "@nuxt/ui/components/Switch.vue";
import { computed, reactive, watch } from "vue";
import type { z } from "zod";
import { platformConfigFormSchema as schema } from "../data.ts";
import type { AdminPlatformConfigItemView as Item } from "../data.ts";

type Input = z.input<typeof schema>;
type Data = z.output<typeof schema>;

const { item } = defineProps<{
  /** The property being changed, or undefined to add one. */
  item?: Item | undefined;
  submit: (data: Data) => Promise<ServiceError | undefined>;
}>();

const open = defineModel<boolean>("open", { required: true });

const draft = reactive<Input>({ section: "", property: "", clientId: "", valueType: "PLAIN", value: "", description: "" });
const editing = computed(() => item !== undefined);
const secret = computed({
  get: () => draft.valueType === "SECRET",
  set: (next: boolean) => {
    draft.valueType = next ? "SECRET" : "PLAIN";
  },
});

const valueHelp = computed(() => (item?.valueType === "SECRET" ? "Enter a new value to change the secret. The current one is not shown." : ""));

watch(
  open,
  (isOpen) => {
    if (isOpen) {
      Object.assign(draft, {
        section: item?.section ?? "",
        property: item?.property ?? "",
        clientId: item?.clientId ?? "",
        valueType: item?.valueType ?? "PLAIN",
        // The plain value is shown; a secret's is not known to the page and is never asked for back.
        value: item === undefined || item.valueType === "SECRET" ? "" : item.value,
        description: item?.description ?? "",
      });
    } else {
      draft.value = "";
    }
  },
  { immediate: true },
);
</script>

<template>
  <FwFormDialog
    v-model:open="open"
    :title="editing ? 'Change a property' : 'Add a property'"
    :description="editing ? 'The section, property and client name the value and cannot change.' : 'Leave the client empty for a value that applies to everyone.'"
    :submit-label="editing ? 'Save' : 'Add'"
    :require-change="editing"
    :schema="schema"
    :state="draft"
    :submit="submit"
  >
    <UFormField label="Section" name="section" required>
      <UInput v-model="draft.section" :disabled="editing" autocomplete="off" class="w-full" data-testid="config-section" />
    </UFormField>
    <UFormField label="Property" name="property" required>
      <UInput v-model="draft.property" :disabled="editing" autocomplete="off" class="w-full" data-testid="config-property" />
    </UFormField>
    <UFormField label="Client" name="clientId" help="The client's id (clt_...) or its code, such as acme. Empty: the global value.">
      <UInput v-model="draft.clientId" :disabled="editing" autocomplete="off" class="w-full" data-testid="config-client" />
    </UFormField>
    <UFormField name="valueType">
      <USwitch v-model="secret" label="This value is a secret" description="A secret is stored encrypted and is never shown again; it can only be revealed on request." data-testid="config-secret" />
    </UFormField>
    <UFormField
      label="Value"
      name="value"
      required
      :help="valueHelp"
    >
      <UInput v-model="draft.value" :type="secret ? 'password' : 'text'" autocomplete="off" class="w-full" data-testid="config-value" />
    </UFormField>
    <UFormField label="Description" name="description">
      <UInput v-model="draft.description" autocomplete="off" class="w-full" data-testid="config-description" />
    </UFormField>
  </FwFormDialog>
</template>
