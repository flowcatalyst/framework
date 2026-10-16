<script setup lang="ts">
// The demo's own admin section: a plain page on the generated getAdminOrdersOverview. M4e's sections
// are lists, drawers and forms built from the kit's partials in the same way.
import { ApiRequestError, toServiceError } from "@flowcatalyst/api-runtime";
import { FwDetailField, FwDetailList } from "@flowcatalyst/ui";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import { onMounted, shallowRef } from "vue";
import { getAdminOrdersOverview } from "../../api/orders/sdk.gen.ts";
import type { OrdersOverview } from "../../api/orders/types.gen.ts";
import { money } from "../orders.ts";

const overview = shallowRef<OrdersOverview>();
const failure = shallowRef<string>();

onMounted(async () => {
  try {
    const { data, error, response } = await getAdminOrdersOverview();
    if (data === undefined) {
      throw new ApiRequestError(toServiceError(error, response));
    }
    overview.value = data;
  } catch (cause) {
    failure.value = cause instanceof ApiRequestError ? cause.message : "The overview could not be loaded.";
  }
});
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="Orders overview" description="How many orders are open and cancelled." />
    <UAlert v-if="failure !== undefined" color="error" variant="subtle" title="Could not load" :description="failure" />
    <FwDetailList v-else-if="overview !== undefined" data-testid="admin-orders-overview">
      <FwDetailField label="Open orders" :value="overview.open" />
      <FwDetailField label="Cancelled orders" :value="overview.cancelled" />
      <FwDetailField label="Value of open orders" :value="money(overview.openTotalCents)" />
    </FwDetailList>
  </div>
</template>
