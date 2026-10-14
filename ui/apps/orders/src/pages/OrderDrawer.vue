<script setup lang="ts">
// The record drawer of /orders/:id (useDrawerRoute: deep link, Back closes, the list's query kept).
// Adding a line is an FwFormDialog on the generated zAddLineBody (unitPriceCents is an int64 and
// stays a number); cancelling asks first (useConfirm) after the reason is typed.
import { toServiceError, type ServiceError } from "@flowcatalyst/api-runtime";
import {
  FwDetailField,
  FwDetailList,
  FwFormDialog,
  FwFormSection,
  FwRecordDrawer,
  FwStatusBadge,
  useConfirm,
  useDirtyForm,
  useDrawerRoute,
  useFlash,
  usePermissions,
} from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import UInputNumber from "@nuxt/ui/components/InputNumber.vue";
import { computed, inject, reactive, shallowRef, watch } from "vue";
import { addOrderLine, cancelOrder, getOrder } from "../api/orders/sdk.gen.ts";
import type { AddLineBody, CancelOrderBody, OrderView } from "../api/orders/types.gen.ts";
import { zAddLineBody } from "../api/orders/zod.gen.ts";
import { MANAGE } from "../pages.ts";
import { money, REFRESH_ORDERS, STATUS_COLORS } from "./orders.ts";

const refreshList = inject(REFRESH_ORDERS, () => Promise.resolve());
const flash = useFlash();
const permissions = usePermissions();
const canManage = computed(() => permissions.can(MANAGE));
const confirm = useConfirm();
const cancelDraft = reactive<CancelOrderBody>({ reason: "" });
const cancelForm = useDirtyForm(() => cancelDraft);
const drawer = useDrawerRoute({ listPath: "/orders", dirty: cancelForm.dirty });

const order = shallowRef<OrderView | undefined>(undefined);
const loadError = shallowRef<string | undefined>(undefined);

async function load(id: string): Promise<void> {
  const { data, error, response } = await getOrder({ path: { orderId: id } });
  if (data === undefined) {
    order.value = undefined;
    loadError.value = toServiceError(error, response).message;
    return;
  }
  loadError.value = undefined;
  order.value = data;
}

watch(
  drawer.id,
  (id) => {
    if (id !== undefined) {
      order.value = undefined;
      void load(id);
    }
  },
  { immediate: true },
);

const title = computed(() => order.value?.id ?? drawer.id.value ?? "Order");
const open = computed(() => order.value?.status === "OPEN");

// Add a line.
const adding = shallowRef(false);
const line = reactive<AddLineBody>({ sku: "", quantity: 1, unitPriceCents: 0 });
function startAdd(): void {
  Object.assign(line, { sku: "", quantity: 1, unitPriceCents: 0 });
  adding.value = true;
}
async function add(body: AddLineBody): Promise<ServiceError | undefined> {
  const id = drawer.id.value;
  if (id === undefined) {
    return undefined;
  }
  const { data, error, response } = await addOrderLine({ path: { orderId: id }, body });
  if (data === undefined) {
    return toServiceError(error, response);
  }
  order.value = data;
  flash.success(`Line ${body.sku} added.`);
  await refreshList();
  return undefined;
}

// Cancel: the reason is typed in the drawer (the API requires one), the button asks first
// (useConfirm). A cancel that finds the order changed answers 409: the message is shown and the
// order is read again so the drawer shows what is true now.
async function cancel(): Promise<void> {
  const current = order.value;
  if (current === undefined) {
    return;
  }
  const confirmed = await confirm({
    title: `Cancel ${current.id}?`,
    description: "A cancelled order cannot change.",
    confirmLabel: "Cancel order",
    cancelLabel: "Keep order",
    color: "error",
  });
  if (!confirmed) {
    return;
  }
  const { data, error, response } = await cancelOrder({ path: { orderId: current.id }, body: { reason: cancelDraft.reason.trim() } });
  if (data === undefined) {
    const failure = toServiceError(error, response);
    flash.error(failure.message);
    if (failure.status === 409) {
      void load(current.id);
      void refreshList();
    }
    return;
  }
  order.value = data;
  cancelDraft.reason = "";
  cancelForm.markClean();
  flash.success(`${data.id} cancelled.`);
  await refreshList();
}
</script>

<template>
  <FwRecordDrawer :open="true" :title="title" :subtitle="order?.customer ?? ''" @close="drawer.close">
    <template #extras>
      <FwStatusBadge v-if="order !== undefined" :status="order.status" :colors="STATUS_COLORS" />
    </template>
    <p v-if="loadError !== undefined" class="text-sm text-error" role="alert" data-testid="drawer-error">{{ loadError }}</p>
    <template v-if="order !== undefined">
      <FwFormSection title="Order">
        <FwDetailList>
          <FwDetailField label="Customer" :value="order.customer" />
          <FwDetailField label="Version" :value="order.version" />
          <FwDetailField label="Lines" :value="order.lines.length" />
          <FwDetailField label="Total" :value="money(order.totalCents)" />
        </FwDetailList>
      </FwFormSection>
      <FwFormSection title="Lines">
        <template v-if="canManage" #actions>
          <UButton label="Add line" icon="i-lucide-plus" size="sm" :disabled="!open" data-testid="add-line" @click="startAdd" />
        </template>
        <table class="w-full text-sm" data-testid="lines">
          <caption class="sr-only">Lines of {{ order.id }}</caption>
          <thead>
            <tr class="text-left text-muted">
              <th class="py-1 font-medium">SKU</th>
              <th class="py-1 text-right font-medium">Qty</th>
              <th class="py-1 text-right font-medium">Unit</th>
              <th class="py-1 text-right font-medium">Total</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in order.lines" :key="l.lineId" class="border-t border-default">
              <td class="py-1 pr-2 font-mono text-xs break-all">{{ l.sku }}</td>
              <td class="py-1 pl-2 text-right tabular-nums">{{ l.quantity }}</td>
              <td class="py-1 pl-2 text-right tabular-nums">{{ money(l.unitPriceCents) }}</td>
              <td class="py-1 pl-2 text-right tabular-nums">{{ money(l.totalCents) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="order.lines.length === 0" class="mt-2 text-sm text-muted">No lines yet.</p>
      </FwFormSection>
      <FwFormSection v-if="canManage && open" title="Cancel" description="Say why; the order cannot change afterwards.">
        <UFormField label="Reason" name="reason">
          <UInput v-model="cancelDraft.reason" class="w-full" data-testid="cancel-reason" />
        </UFormField>
        <UButton
          label="Cancel order…"
          color="error"
          variant="outline"
          class="mt-3"
          :disabled="cancelDraft.reason.trim() === ''"
          data-testid="cancel-order"
          @click="cancel"
        />
      </FwFormSection>
    </template>
    <template #footer>
      <UButton label="Close" color="neutral" variant="ghost" @click="drawer.close" />
    </template>
  </FwRecordDrawer>
  <FwFormDialog v-model:open="adding" title="Add a line" submit-label="Add line" :schema="zAddLineBody" :state="line" :submit="add">
    <UFormField label="SKU" name="sku" required help="Upper-case letters, digits and dashes.">
      <UInput v-model="line.sku" class="w-full" data-testid="line-sku" />
    </UFormField>
    <UFormField label="Quantity" name="quantity" required>
      <UInputNumber v-model="line.quantity" :min="1" :max="10000" data-testid="line-quantity" />
    </UFormField>
    <UFormField label="Unit price (cents)" name="unitPriceCents" required>
      <UInputNumber v-model="line.unitPriceCents" :min="0" :max="100000000" data-testid="line-price" />
    </UFormField>
  </FwFormDialog>
</template>
