<script setup lang="ts">
// The record drawer of /admin/event-types/:id (useDrawerRoute: deep link, Back closes, the list's query
// kept): the event type's fields and its payload specification versions, read-only.
import {
  FwCodeBlock,
  FwCodeChips,
  FwDetailField,
  FwDetailList,
  FwFormSection,
  FwRecordDrawer,
  FwStatusBadge,
  useDrawerRoute,
} from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import { computed, shallowRef, watch } from "vue";
import { getAdminEventType } from "../../api/orders/sdk.gen.ts";
import type { AdminEventTypeDetail } from "../../api/orders/types.gen.ts";
import { dataOrThrow, EVENT_TYPE_COLORS, failureText } from "./platform.ts";

const drawer = useDrawerRoute({ listPath: "/admin/event-types" });
const detail = shallowRef<AdminEventTypeDetail | undefined>(undefined);
const loadError = shallowRef<string | undefined>(undefined);

async function load(id: string): Promise<void> {
  try {
    detail.value = dataOrThrow(await getAdminEventType({ path: { id } }));
    loadError.value = undefined;
  } catch (cause) {
    detail.value = undefined;
    loadError.value = failureText(cause, "The event type");
  }
}

watch(
  drawer.id,
  (id) => {
    if (id !== undefined) {
      detail.value = undefined;
      void load(id);
    }
  },
  { immediate: true },
);

const title = computed(() => detail.value?.eventType.name ?? drawer.id.value ?? "Event type");
</script>

<template>
  <FwRecordDrawer :open="true" :title="title" :subtitle="detail?.eventType.code ?? ''" @close="drawer.close">
    <template #extras>
      <FwStatusBadge v-if="detail !== undefined" :status="detail.eventType.status" :colors="EVENT_TYPE_COLORS" />
    </template>
    <p v-if="loadError !== undefined" class="text-sm text-error" role="alert" data-testid="drawer-error">{{ loadError }}</p>
    <template v-if="detail !== undefined">
      <FwFormSection title="Event type">
        <FwDetailList>
          <FwDetailField label="Code"><FwCodeChips :code="detail.eventType.code" /></FwDetailField>
          <FwDetailField label="Name" :value="detail.eventType.name" />
          <FwDetailField label="Description" :value="detail.eventType.description" />
          <FwDetailField label="Subdomain" :value="detail.eventType.subdomain" />
          <FwDetailField label="Aggregate" :value="detail.eventType.aggregate" />
          <FwDetailField label="Event" :value="detail.eventType.event" />
        </FwDetailList>
      </FwFormSection>
      <FwFormSection title="Specification versions" description="The payload schema each version of the event carries.">
        <p v-if="detail.versions.length === 0" class="text-sm text-muted" data-testid="no-versions">No specification versions yet.</p>
        <div v-for="version in detail.versions" :key="version.version" class="mb-4 space-y-2" data-testid="spec-version">
          <div class="flex items-center gap-2 text-sm">
            <span class="font-medium text-highlighted">Version {{ version.version }}</span>
            <span class="text-muted">{{ version.status.toLowerCase() }}</span>
          </div>
          <FwCodeBlock
            v-if="version.schema !== undefined"
            :code="version.schema"
            language="json"
            :label="`Schema of version ${version.version}`"
          />
          <p v-else class="text-sm text-muted">No schema.</p>
        </div>
      </FwFormSection>
    </template>
    <template #footer>
      <UButton label="Close" color="neutral" variant="ghost" @click="drawer.close" />
    </template>
  </FwRecordDrawer>
</template>
