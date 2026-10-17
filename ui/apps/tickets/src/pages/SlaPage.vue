<script setup lang="ts">
// The client's SLA policy: the minutes allowed to resolve a ticket, by priority. Everyone who reads
// tickets sees it; a lead edits it in a form dialog on the generated zSetSlaPolicyBody. A client that
// has set none sees the defaults and is told so. It applies to tickets opened afterwards.
import { toServiceError, type ServiceError } from "@flowcatalyst/api-runtime";
import { FwDetailField, FwDetailList, FwFormDialog, FwStatusBadge, useFlash, usePermissions } from "@flowcatalyst/ui";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInputNumber from "@nuxt/ui/components/InputNumber.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import { computed, onMounted, reactive, shallowRef } from "vue";
import { getSlaPolicy, setSlaPolicy } from "../api/tickets/sdk.gen.ts";
import type { SetSlaPolicyBody, SlaPolicyView } from "../api/tickets/types.gen.ts";
import { zSetSlaPolicyBody } from "../api/tickets/zod.gen.ts";
import { SLA_MANAGE } from "../pages.ts";
import { duration, PRIORITY_COLORS } from "./tickets.ts";

const flash = useFlash();
const permissions = usePermissions();
const canManage = computed(() => permissions.can(SLA_MANAGE));

const policy = shallowRef<SlaPolicyView>();
const failure = shallowRef<string>();

async function load(): Promise<void> {
  const { data, error, response } = await getSlaPolicy();
  if (data === undefined) {
    failure.value = toServiceError(error, response).message;
    return;
  }
  failure.value = undefined;
  policy.value = data;
}
onMounted(load);

const editing = shallowRef(false);
const draft = reactive<SetSlaPolicyBody>({ lowMinutes: 1, normalMinutes: 1, highMinutes: 1, urgentMinutes: 1 });
function startEdit(): void {
  const current = policy.value;
  if (current === undefined) {
    return;
  }
  Object.assign(draft, { lowMinutes: current.lowMinutes, normalMinutes: current.normalMinutes, highMinutes: current.highMinutes, urgentMinutes: current.urgentMinutes });
  editing.value = true;
}
async function save(body: SetSlaPolicyBody): Promise<ServiceError | undefined> {
  const { data, error, response } = await setSlaPolicy({ body });
  if (data === undefined) {
    return toServiceError(error, response);
  }
  policy.value = data;
  flash.success("SLA policy saved. It applies to tickets opened from now on.");
  return undefined;
}

const rows = computed(() =>
  policy.value === undefined
    ? []
    : [
        { priority: "URGENT", minutes: policy.value.urgentMinutes },
        { priority: "HIGH", minutes: policy.value.highMinutes },
        { priority: "NORMAL", minutes: policy.value.normalMinutes },
        { priority: "LOW", minutes: policy.value.lowMinutes },
      ],
);
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="SLA policy" description="How long an agent has to resolve a ticket, by priority. A ticket's deadline is fixed when it is opened.">
      <template v-if="canManage && policy !== undefined" #links>
        <UButton label="Edit policy" icon="i-lucide-pencil" data-testid="edit-sla" @click="startEdit" />
      </template>
    </UPageHeader>
    <UAlert v-if="failure !== undefined" color="error" variant="subtle" title="Could not load" :description="failure" data-testid="sla-error" />
    <template v-else-if="policy !== undefined">
      <UAlert
        v-if="!policy.customised"
        color="info"
        variant="subtle"
        title="Using the defaults"
        description="Your client has not set a policy of its own."
        data-testid="sla-defaults"
      />
      <FwDetailList data-testid="sla-table">
        <FwDetailField v-for="row in rows" :key="row.priority" :label="row.priority.charAt(0) + row.priority.slice(1).toLowerCase()" :value="duration(row.minutes)" />
      </FwDetailList>
      <p class="flex items-center gap-2 text-sm text-muted">
        Priorities:
        <FwStatusBadge v-for="row in rows" :key="row.priority" :status="row.priority" :colors="PRIORITY_COLORS" />
      </p>
    </template>
  </div>
  <FwFormDialog
    v-model:open="editing"
    title="Edit SLA policy"
    description="Minutes to resolve a ticket. A more urgent ticket cannot be given more time."
    submit-label="Save policy"
    require-change
    :schema="zSetSlaPolicyBody"
    :state="draft"
    :submit="save"
  >
    <UFormField label="Urgent (minutes)" name="urgentMinutes" required>
      <UInputNumber v-model="draft.urgentMinutes" :min="1" :max="43200" data-testid="sla-urgent" />
    </UFormField>
    <UFormField label="High (minutes)" name="highMinutes" required>
      <UInputNumber v-model="draft.highMinutes" :min="1" :max="43200" data-testid="sla-high" />
    </UFormField>
    <UFormField label="Normal (minutes)" name="normalMinutes" required>
      <UInputNumber v-model="draft.normalMinutes" :min="1" :max="43200" data-testid="sla-normal" />
    </UFormField>
    <UFormField label="Low (minutes)" name="lowMinutes" required>
      <UInputNumber v-model="draft.lowMinutes" :min="1" :max="43200" data-testid="sla-low" />
    </UFormField>
  </FwFormDialog>
</template>
