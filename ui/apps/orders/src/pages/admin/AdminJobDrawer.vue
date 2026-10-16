<script setup lang="ts">
// The record drawer of /admin/activity/jobs/:id: the dispatch job, where it is delivered to (the
// subscriber's endpoint, which may belong to another application) and every delivery attempt with the
// subscriber's answer. Read-only.
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
import { getAdminDispatchJob } from "../../api/orders/sdk.gen.ts";
import type { AdminDispatchJobDetail } from "../../api/orders/types.gen.ts";
import { dataOrThrow, failureText, JOB_COLORS } from "./platform.ts";

const route = useRoute();
const drawer = useDrawerRoute({ listPath: "/admin/activity" });
const detail = shallowRef<AdminDispatchJobDetail | undefined>(undefined);
const loadError = shallowRef<string | undefined>(undefined);

async function load(id: string): Promise<void> {
  try {
    detail.value = dataOrThrow(await getAdminDispatchJob({ path: { id } }));
    loadError.value = undefined;
  } catch (cause) {
    detail.value = undefined;
    loadError.value = failureText(cause, "The dispatch job");
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

const title = computed(() => detail.value?.job.id ?? drawer.id.value ?? "Dispatch job");
</script>

<template>
  <FwRecordDrawer :open="true" :title="title" :subtitle="detail?.job.code ?? ''" @close="drawer.close">
    <template #extras>
      <FwStatusBadge v-if="detail !== undefined" :status="detail.job.status" :colors="JOB_COLORS" />
    </template>
    <p v-if="loadError !== undefined" class="text-sm text-error" role="alert" data-testid="drawer-error">{{ loadError }}</p>
    <template v-if="detail !== undefined">
      <FwFormSection title="Dispatch job">
        <FwDetailList>
          <FwDetailField label="Code"><FwCodeChips :code="detail.job.code" /></FwDetailField>
          <FwDetailField label="Event">
            <RouterLink v-if="detail.job.eventId !== undefined" :to="{ path: `/admin/activity/events/${detail.job.eventId}`, query: route.query }" class="font-mono text-[13px] underline-offset-2 hover:underline">{{ detail.job.eventId }}</RouterLink>
          </FwDetailField>
          <FwDetailField label="Kind" :value="detail.job.kind" />
          <FwDetailField label="Created"><FwLocalTime :at="detail.job.createdAt" /></FwDetailField>
          <FwDetailField label="Last tried"><FwLocalTime :at="detail.job.lastAttemptAt" empty="Not yet" /></FwDetailField>
          <FwDetailField label="Finished"><FwLocalTime :at="detail.job.completedAt" empty="Not yet" /></FwDetailField>
          <FwDetailField label="Attempts" :value="detail.job.attemptCount" />
          <FwDetailField label="Subscription" :value="detail.job.descriptor ?? detail.job.subscriptionId" />
          <FwDetailField label="Message group" :value="detail.job.messageGroup" />
          <FwDetailField label="Last error" :value="detail.lastError" span />
        </FwDetailList>
      </FwFormSection>
      <FwFormSection
        title="Delivery target"
        description="Where the platform delivers this event. The endpoint belongs to the subscription that asked for it, which may be another application's."
      >
        <FwDetailList>
          <FwDetailField label="Delivered to" :value="detail.job.targetUrl" span data-testid="job-target" />
          <FwDetailField label="Protocol" :value="detail.protocol" />
          <FwDetailField label="Timeout" :value="detail.timeoutSeconds === undefined ? undefined : `${String(detail.timeoutSeconds)} s`" />
          <FwDetailField label="Retries" :value="detail.maxRetries === undefined ? undefined : `${String(detail.maxRetries)} (${detail.retryStrategy ?? 'default'})`" />
        </FwDetailList>
      </FwFormSection>
      <FwFormSection title="Attempts" description="Each delivery and what the endpoint answered.">
        <p v-if="detail.attempts.length === 0" class="text-sm text-muted" data-testid="no-attempts">No attempts yet.</p>
        <div v-for="attempt in detail.attempts" :key="attempt.attemptNumber" class="mb-4 space-y-2" data-testid="attempt">
          <div class="flex flex-wrap items-center gap-2 text-sm">
            <span class="font-medium text-highlighted">Attempt {{ attempt.attemptNumber }}</span>
            <FwStatusBadge :status="attempt.success ? 'DELIVERED' : 'FAILED'" :colors="{ DELIVERED: 'success', FAILED: 'error' }" />
            <span v-if="attempt.responseCode !== undefined" class="text-muted">HTTP {{ attempt.responseCode }}</span>
            <span v-if="attempt.durationMillis !== undefined" class="text-muted">{{ attempt.durationMillis }} ms</span>
            <FwLocalTime :at="attempt.attemptedAt" />
          </div>
          <p v-if="attempt.errorMessage !== undefined" class="text-sm text-error [overflow-wrap:anywhere]">
            {{ attempt.errorType === undefined ? "" : `${attempt.errorType}: ` }}{{ attempt.errorMessage }}
          </p>
          <FwCodeBlock v-if="attempt.responseBody !== undefined" :code="attempt.responseBody" language="text" :label="`Answer to attempt ${String(attempt.attemptNumber)}`" />
          <p v-if="attempt.responseBodyTruncated" class="text-sm text-muted" data-testid="answer-truncated">The answer is long: only the start is shown.</p>
        </div>
      </FwFormSection>
    </template>
    <template #footer>
      <UButton label="Close" color="neutral" variant="ghost" @click="drawer.close" />
    </template>
  </FwRecordDrawer>
</template>
