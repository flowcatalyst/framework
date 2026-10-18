<script setup lang="ts">
// The record drawer of /admin/roles/:id (the route param is the role's full name, `app:role`):
// the role and the permissions it grants, read-only.
import FwDetailField from "../../components/FwDetailField.vue";
import FwDetailList from "../../components/FwDetailList.vue";
import FwFormSection from "../../components/FwFormSection.vue";
import FwRecordDrawer from "../../partials/FwRecordDrawer.vue";
import { useDrawerRoute } from "../../composables/useDrawerRoute.ts";
import UButton from "@nuxt/ui/components/Button.vue";
import { computed, shallowRef, watch } from "vue";
import { requireCall, useAdminData } from "../data.ts";
import type { AdminRoleDetailView as AdminRoleDetail } from "../data.ts";
import { dataOrThrow, failureText } from "../shared.ts";

const admin = useAdminData();
const drawer = useDrawerRoute({ listPath: "/admin/roles" });
const detail = shallowRef<AdminRoleDetail | undefined>(undefined);
const loadError = shallowRef<string | undefined>(undefined);

async function load(name: string): Promise<void> {
  try {
    detail.value = dataOrThrow(await requireCall(admin, "getAdminRole")({ path: { name } }));
    loadError.value = undefined;
  } catch (cause) {
    detail.value = undefined;
    loadError.value = failureText(cause, "The role");
  }
}

watch(
  drawer.id,
  (name) => {
    if (name !== undefined) {
      detail.value = undefined;
      void load(name);
    }
  },
  { immediate: true },
);

const title = computed(() => detail.value?.role.displayName ?? drawer.id.value ?? "Role");
</script>

<template>
  <FwRecordDrawer :open="true" :title="title" :subtitle="detail?.role.name ?? ''" @close="drawer.close">
    <p v-if="loadError !== undefined" class="text-sm text-error" role="alert" data-testid="drawer-error">{{ loadError }}</p>
    <template v-if="detail !== undefined">
      <FwFormSection title="Role">
        <FwDetailList>
          <FwDetailField label="Name" :value="detail.role.name" />
          <FwDetailField label="Display name" :value="detail.role.displayName" />
          <FwDetailField label="Description" :value="detail.role.description" />
          <FwDetailField label="Source" :value="detail.role.source" />
          <FwDetailField label="Client managed" :value="detail.role.clientManaged ? 'Yes' : 'No'" />
        </FwDetailList>
      </FwFormSection>
      <FwFormSection title="Permissions" :description="`${String(detail.permissions.length)} granted`">
        <p v-if="detail.permissions.length === 0" class="text-sm text-muted">This role grants nothing.</p>
        <ul v-else class="divide-y divide-default rounded-md border border-default" data-testid="role-permissions">
          <li v-for="permission in detail.permissions" :key="permission" class="px-3 py-2 font-mono text-[13px] break-all">{{ permission }}</li>
        </ul>
      </FwFormSection>
    </template>
    <template #footer>
      <UButton label="Close" color="neutral" variant="ghost" @click="drawer.close" />
    </template>
  </FwRecordDrawer>
</template>
