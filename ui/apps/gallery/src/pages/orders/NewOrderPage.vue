<script setup lang="ts">
// The create form as a page (FwFormPage) on the generated zPlaceOrderBody, beside the list's New
// order dialog: a route of its own, Cancel and Back go to the list, leaving with changes asks first,
// and the created order's drawer opens over the list. The fake server refuses the customer
// "Blocked Ltd" with a field error, so the server's errors can be shown.
import { toServiceError, type ServiceError } from "@flowcatalyst/api-runtime";
import { FwFormPage, FwFormSection } from "@flowcatalyst/ui";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import { reactive } from "vue";
import { useRouter } from "vue-router";
import { placeOrder } from "../../api/orders-demo/sdk.gen.ts";
import type { OrderView, PlaceOrderBody } from "../../api/orders-demo/types.gen.ts";
import { zPlaceOrderBody } from "../../api/orders-demo/zod.gen.ts";

const router = useRouter();
const draft = reactive<PlaceOrderBody>({ customer: "" });
let created: OrderView | undefined;

async function create(body: PlaceOrderBody): Promise<ServiceError | undefined> {
  const { data, error, response } = await placeOrder({ body });
  if (data === undefined) {
    return toServiceError(error, response);
  }
  created = data;
  return undefined;
}

function saved(): void {
  if (created !== undefined) {
    void router.push(`/orders/${created.id}`);
  }
}
</script>

<template>
  <FwFormPage
    title="New order"
    description="FwFormPage: a create form too long for a dialog, on a route of its own."
    submit-label="Create order"
    back="/orders"
    back-label="Orders"
    :schema="zPlaceOrderBody"
    :state="draft"
    :submit="create"
    @saved="saved"
  >
    <FwFormSection title="Customer" description="Who the order is for." :level="2">
      <UFormField label="Customer" name="customer" required>
        <UInput v-model="draft.customer" class="w-full" data-testid="page-customer" />
      </UFormField>
    </FwFormSection>
    <template #aside>
      <FwFormSection title="About this page" :level="2" card>
        <p class="text-sm text-muted">Lines are added from the order's drawer once it exists. "Blocked Ltd" is refused by the server.</p>
      </FwFormSection>
    </template>
  </FwFormPage>
</template>
