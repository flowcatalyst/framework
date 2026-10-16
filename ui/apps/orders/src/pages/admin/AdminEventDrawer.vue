<script setup lang="ts">
// The record drawer of /admin/activity/events/:id: the event, its payload (cut when long), its context
// and the dispatch jobs that deliver it, each opening the job's drawer. Read-only.
import {
  FwCodeBlock,
  FwCodeChips,
  FwDetailField,
  FwDetailList,
  FwFormSection,
  FwLocalTime,
  FwRecordDrawer,
  FwStatusBadge,
  useDrawerRoute,
} from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import { computed, shallowRef, watch } from "vue";
import { RouterLink, useRoute } from "vue-router";
import { getAdminEvent } from "../../api/orders/sdk.gen.ts";
import type { AdminEventDetail } from "../../api/orders/types.gen.ts";
import { dataOrThrow, failureText, JOB_COLORS } from "./platform.ts";

const route = useRoute();
const drawer = useDrawerRoute({ listPath: "/admin/activity" });
const detail = shallowRef<AdminEventDetail | undefined>(undefined);
const loadError = shallowRef<string | undefined>(undefined);

async function load(id: string): Promise<void> {
  try {
    detail.value = dataOrThrow(await getAdminEvent({ path: { id } }));
    loadError.value = undefined;
  } catch (cause) {
    detail.value = undefined;
    loadError.value = failureText(cause, "The event");
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

const title = computed(() => detail.value?.event.id ?? drawer.id.value ?? "Event");
</script>

<template>
  <FwRecordDrawer :open="true" :title="title" :subtitle="detail?.event.type ?? ''" @close="drawer.close">
    <p v-if="loadError !== undefined" class="text-sm text-error" role="alert" data-testid="drawer-error">{{ loadError }}</p>
    <template v-if="detail !== undefined">
      <FwFormSection title="Event">
        <FwDetailList>
          <FwDetailField label="Type"><FwCodeChips :code="detail.event.type" /></FwDetailField>
          <FwDetailField label="Source" :value="detail.event.source" />
          <FwDetailField label="Subject" :value="detail.event.subject" />
          <FwDetailField label="Happened"><FwLocalTime :at="detail.event.time" /></FwDetailField>
          <FwDetailField label="Client" :value="detail.event.clientId" />
          <FwDetailField label="Correlation id" :value="detail.event.correlationId" />
          <FwDetailField label="Message group" :value="detail.event.messageGroup" />
          <FwDetailField label="Caused by" :value="detail.causationId" />
        </FwDetailList>
      </FwFormSection>
      <FwFormSection title="Payload">
        <FwCodeBlock v-if="detail.data !== undefined" :code="detail.data" language="json" label="Event payload" />
        <p v-else class="text-sm text-muted" data-testid="no-payload">No payload.</p>
        <p v-if="detail.dataTruncated" class="mt-2 text-sm text-muted" data-testid="payload-truncated">The payload is long: only the start is shown.</p>
      </FwFormSection>
      <FwFormSection v-if="detail.context.length > 0" title="Context">
        <FwDetailList>
          <FwDetailField v-for="entry in detail.context" :key="entry.key" :label="entry.key" :value="entry.value" />
        </FwDetailList>
      </FwFormSection>
      <FwFormSection title="Dispatch jobs" description="How this application's event is delivered.">
        <p v-if="detail.dispatchJobs.length === 0" class="text-sm text-muted" data-testid="no-jobs">No dispatch jobs for this event.</p>
        <ul v-else class="space-y-2" data-testid="event-jobs">
          <li v-for="job in detail.dispatchJobs" :key="job.id" class="flex flex-wrap items-center gap-2 text-sm">
            <RouterLink :to="{ path: `/admin/activity/jobs/${job.id}`, query: route.query }" class="font-mono text-[13px] underline-offset-2 hover:underline">{{ job.id }}</RouterLink>
            <FwStatusBadge :status="job.status" :colors="JOB_COLORS" />
            <span class="text-muted [overflow-wrap:anywhere]">to {{ job.targetUrl }}</span>
          </li>
        </ul>
      </FwFormSection>
    </template>
    <template #footer>
      <UButton label="Close" color="neutral" variant="ghost" @click="drawer.close" />
    </template>
  </FwRecordDrawer>
</template>
