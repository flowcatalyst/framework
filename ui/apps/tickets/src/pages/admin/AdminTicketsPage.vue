<script setup lang="ts">
// The service's own admin section: a plain page on the generated getAdminTicketsOverview.
import { adminDataOrThrow as dataOrThrow, adminFailureText as failureText, FwDetailField, FwDetailList, FwStatusBadge } from "@flowcatalyst/ui";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import { onMounted, shallowRef } from "vue";
import { getAdminTicketsOverview } from "../../api/tickets/sdk.gen.ts";
import type { TicketsOverview } from "../../api/tickets/types.gen.ts";
import { STATUS_COLORS } from "../tickets.ts";

const overview = shallowRef<TicketsOverview>();
const failure = shallowRef<string>();

onMounted(async () => {
  try {
    overview.value = dataOrThrow(await getAdminTicketsOverview());
  } catch (cause) {
    failure.value = failureText(cause, "The overview");
  }
});
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="Queue overview" description="How many tickets are in each status, and how many ran out of time." />
    <UAlert v-if="failure !== undefined" color="error" variant="subtle" title="Could not load" :description="failure" />
    <template v-else-if="overview !== undefined">
      <FwDetailList data-testid="admin-tickets-overview">
        <FwDetailField label="Active tickets" :value="overview.active" />
        <FwDetailField label="Active and past their deadline" :value="overview.flagged" />
      </FwDetailList>
      <table class="w-full max-w-md text-sm" data-testid="admin-tickets-statuses">
        <caption class="sr-only">Tickets by status</caption>
        <thead>
          <tr class="text-left text-muted">
            <th class="py-1 font-medium">Status</th>
            <th class="py-1 text-right font-medium">Tickets</th>
            <th class="py-1 text-right font-medium">Past deadline</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in overview.statuses" :key="s.status" class="border-t border-default">
            <td class="py-1"><FwStatusBadge :status="s.status" :colors="STATUS_COLORS" /></td>
            <td class="py-1 text-right tabular-nums">{{ s.count }}</td>
            <td class="py-1 text-right tabular-nums">{{ s.flagged }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="overview.statuses.length === 0" class="text-sm text-muted">No tickets yet.</p>
    </template>
  </div>
</template>
