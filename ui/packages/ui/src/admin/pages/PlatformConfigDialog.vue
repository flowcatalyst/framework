<script setup lang="ts">
// Add or change one platform config property (platform config contract section 5): the section, the
// property, the client and the value in FwFormDialog. A secret's value goes in a password field that is
// never filled in, not even when a secret is edited (the platform never returns it): changing a secret
// needs a new value, and the platform's mask `***` is refused as one. Closing the dialog empties the
// draft, so a typed value does not outlive it.
//
// The client (contract section 4, Validation): a user holding `{app}:admin:platform-config:all-clients`
// names any client in a free field; anyone else chooses between the global value and the session's own
// client (its identifier, which is what the admin module accepts before it has learned the stored id),
// and a session for no particular client (`_platform`) manages global values only.
import type { ServiceError } from "@flowcatalyst/api-runtime";
import FwFormDialog from "../../partials/FwFormDialog.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import URadioGroup from "@nuxt/ui/components/RadioGroup.vue";
import USwitch from "@nuxt/ui/components/Switch.vue";
import { computed, reactive, watch } from "vue";
import type { z } from "zod";
import { platformConfigFormSchema as schema } from "../data.ts";
import type { AdminPlatformConfigItemView as Item } from "../data.ts";

type Input = z.input<typeof schema>;
type Data = z.output<typeof schema>;

const { item, allClients = false, ownClient } = defineProps<{
  /** The property being changed, or undefined to add one. */
  item?: Item | undefined;
  submit: (data: Data) => Promise<ServiceError | undefined>;
  /** The session holds `all-clients`: the client is a free field. */
  allClients?: boolean;
  /** The session's own client identifier (`acme`), or undefined for a session for no particular client. */
  ownClient?: string | undefined;
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

// The scope choice of a user without all-clients: the global value or the own client.
const scopeItems = computed(() => [
  { label: "Global", description: "The value for every client.", value: "global" },
  { label: `Your client, ${ownClient ?? ""}`, description: "The value for your client only.", value: "own" },
]);
const scopeChoice = computed({
  get: () => (draft.clientId === "" ? "global" : "own"),
  set: (next: string) => {
    draft.clientId = next === "own" && ownClient !== undefined ? ownClient : "";
  },
});

/** The client as the fixed field of an edit shows it. */
const editedClient = computed(() => {
  const id = item?.clientId;
  if (id === undefined) {
    return "Global";
  }
  return allClients || ownClient === undefined ? id : `${ownClient} (${id})`;
});

const dialogDescription = computed(() => {
  if (editing.value) {
    return "The section, property and client name the value and cannot change.";
  }
  if (allClients) {
    return "Leave the client empty for a value that applies to everyone.";
  }
  return ownClient === undefined
    ? "You are signed in for no particular client, so the value is global: it applies to everyone."
    : "Choose the global value or your own client's.";
});

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
    :description="dialogDescription"
    :submit-label="editing ? 'Save' : 'Add'"
    :require-change="editing"
    :schema="schema"
    :state="draft"
    :submit="submit"
  >
    <UFormField label="Section" name="section" required>
      <UInput v-model="draft.section" :disabled="editing" autocomplete="off" class="w-full" data-testid="config-section" />
    </UFormField>
    <UFormField
      label="Property"
      name="property"
      required
      help="Lower-case letters and digits in groups joined by single hyphens, at most 100 characters: the same rule as a secret name."
    >
      <UInput v-model="draft.property" :disabled="editing" autocomplete="off" class="w-full" data-testid="config-property" />
    </UFormField>
    <UFormField v-if="editing" label="Client" name="clientId">
      <UInput :model-value="editedClient" disabled class="w-full" data-testid="config-client" />
    </UFormField>
    <UFormField v-else-if="allClients" label="Client" name="clientId" help="The client's id (clt_...) or its code, such as acme. Empty: the global value.">
      <UInput v-model="draft.clientId" autocomplete="off" class="w-full" data-testid="config-client" />
    </UFormField>
    <UFormField v-else-if="ownClient !== undefined" label="Applies to" name="clientId">
      <URadioGroup v-model="scopeChoice" :items="scopeItems" data-testid="config-scope" />
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
