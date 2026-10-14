<script setup lang="ts">
// The record drawer of /orders/:id (useDrawerRoute: deep link, Back closes, the list's query kept,
// "Discard changes?" when the note is edited and the user leaves or switches rows). Adding a line is
// an FwFormDialog on the generated zAddLineBody: unitPriceCents is an int64 and stays a number, so
// the body is plain JSON (docs/ui/u2.md). Cancelling asks first (useConfirm).
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
} from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import UInputNumber from "@nuxt/ui/components/InputNumber.vue";
import UTextarea from "@nuxt/ui/components/Textarea.vue";
import { computed, inject, reactive, shallowRef, watch } from "vue";
import { addOrderLine, cancelOrder, getOrder } from "../../api/orders-demo/sdk.gen.ts";
import type { AddLineBody, OrderView } from "../../api/orders-demo/types.gen.ts";
import { zAddLineBody } from "../../api/orders-demo/zod.gen.ts";
import { requests } from "../../fake-server.ts";
import { money, REFRESH_ORDERS, STATUS_SEVERITY } from "./orders.ts";

const refreshList = inject(REFRESH_ORDERS, () => Promise.resolve());
const confirm = useConfirm();
const flash = useFlash();

// A local note (not part of the API) to show the dirty guard of the drawer route.
const note = reactive({ text: "" });
const noteForm = useDirtyForm(() => note);
const drawer = useDrawerRoute({ listPath: "/orders", dirty: noteForm.dirty });

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
  note.text = "";
  noteForm.markClean();
}

watch(
  drawer.id,
  (id) => {
    if (id !== undefined) {
      void load(id);
    }
  },
  { immediate: true },
);

const title = computed(() => order.value?.id ?? drawer.id.value ?? "Order");

// Add a line.
const adding = shallowRef(false);
const line = reactive<AddLineBody>({ sku: "", quantity: 1, unitPriceCents: 0 });
const lastBody = computed(() => [...requests].reverse().find((r) => r.path.endsWith("/lines"))?.body ?? "");
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

async function cancel(): Promise<void> {
  const current = order.value;
  if (current === undefined) {
    return;
  }
  const confirmed = await confirm({
    title: `Cancel ${current.id}?`,
    message: "The customer is told at once. A cancelled order cannot change.",
    confirmLabel: "Cancel order",
    cancelLabel: "Keep order",
    tone: "danger",
  });
  if (!confirmed) {
    return;
  }
  const { data, error, response } = await cancelOrder({ path: { orderId: current.id }, body: { reason: "Cancelled in the gallery" } });
  if (data === undefined) {
    flash.error(toServiceError(error, response).message);
    return;
  }
  order.value = data;
  flash.success(`${data.id} cancelled.`);
  await refreshList();
}
</script>

<template>
  <FwRecordDrawer :open="true" :title="title" :subtitle="order?.customer ?? ''" @close="drawer.close">
    <template #extras>
      <FwStatusBadge v-if="order !== undefined" :status="order.status" :severities="STATUS_SEVERITY" />
    </template>
    <p v-if="loadError !== undefined" class="text-sm text-error" role="alert">{{ loadError }}</p>
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
        <template #actions>
          <UButton label="Add line" icon="i-lucide-plus" size="sm" :disabled="order.status !== 'OPEN'" data-testid="add-line" @click="startAdd" />
        </template>
        <table class="w-full text-sm" data-testid="lines">
          <thead>
            <tr class="text-left text-muted"><th class="py-1 font-medium">SKU</th><th class="py-1 text-right font-medium">Qty</th><th class="py-1 text-right font-medium">Unit</th></tr>
          </thead>
          <tbody>
            <tr v-for="l in order.lines" :key="l.lineId" class="border-t border-default">
              <td class="py-1 font-mono">{{ l.sku }}</td>
              <td class="py-1 text-right tabular-nums">{{ l.quantity }}</td>
              <td class="py-1 text-right tabular-nums">{{ money(l.unitPriceCents) }}</td>
            </tr>
          </tbody>
        </table>
        <p class="mt-2 text-xs text-muted">Last body sent: <code data-testid="last-body">{{ lastBody }}</code></p>
      </FwFormSection>
      <FwFormSection title="Note" description="Edit it, then press Back or pick another row: the drawer asks first.">
        <UTextarea v-model="note.text" class="w-full" aria-label="Note" data-testid="note" />
      </FwFormSection>
    </template>
    <template #footer>
      <UButton label="Cancel order…" color="error" variant="outline" :disabled="order?.status !== 'OPEN'" data-testid="cancel-order" @click="cancel" />
      <UButton label="Close" color="neutral" variant="ghost" @click="drawer.close" />
    </template>
  </FwRecordDrawer>
  <FwFormDialog v-model:open="adding" title="Add a line" submit-label="Add line" :schema="zAddLineBody" :state="line" :submit="add">
    <UFormField label="SKU" name="sku" required help="Upper-case letters, digits and dashes. TAKEN SKUs come back as a server error.">
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
