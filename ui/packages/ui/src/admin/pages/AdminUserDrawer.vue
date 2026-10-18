<script setup lang="ts">
// The record drawer of /admin/users/:id: the user, the application's roles they hold and, while they
// await an invitation, its state with Resend (by email, or as a link shown once) and Revoke, each
// asked for first (useConfirm). A failure is shown in the platform's words; the invitation link is
// only ever in the dialog that shows it.
import { toServiceError } from "@flowcatalyst/api-runtime";
import FwDetailField from "../../components/FwDetailField.vue";
import FwDetailList from "../../components/FwDetailList.vue";
import FwFormSection from "../../components/FwFormSection.vue";
import FwLocalTime from "../../components/FwLocalTime.vue";
import FwRecordDrawer from "../../partials/FwRecordDrawer.vue";
import FwStatusBadge from "../../components/FwStatusBadge.vue";
import { useConfirm } from "../../composables/useConfirm.ts";
import { useDrawerRoute } from "../../composables/useDrawerRoute.ts";
import { useFlash } from "../../composables/useFlash.ts";
import { usePermissions } from "../../composables/useSession.ts";
import UButton from "@nuxt/ui/components/Button.vue";
import { computed, inject, shallowRef, watch } from "vue";
import { requireCall, useAdminData } from "../data.ts";
import type { AdminUserView as AdminUser } from "../data.ts";
import InviteLinkDialog from "./InviteLinkDialog.vue";
import { dataOrThrow, failureText, INVITATION_COLORS, REFRESH_USERS } from "../shared.ts";

const admin = useAdminData();
const refreshList = inject(REFRESH_USERS, () => Promise.resolve());
const flash = useFlash();
const confirm = useConfirm();
const permissions = usePermissions();
const canResend = computed(() => permissions.can(admin.permissions.usersResend));
const canRevoke = computed(() => permissions.can(admin.permissions.usersRevoke));
const drawer = useDrawerRoute({ listPath: "/admin/users" });

const user = shallowRef<AdminUser | undefined>(undefined);
const loadError = shallowRef<string | undefined>(undefined);
const busy = shallowRef(false);
// The link a resend returned, shown once and dropped when the dialog closes.
const shownLink = shallowRef<{ link: string; expiresAt: string | undefined } | undefined>(undefined);

async function load(id: string): Promise<void> {
  try {
    user.value = dataOrThrow(await requireCall(admin, "getAdminUser")({ path: { id } }));
    loadError.value = undefined;
  } catch (cause) {
    user.value = undefined;
    loadError.value = failureText(cause, "The user");
  }
}

watch(
  drawer.id,
  (id) => {
    if (id !== undefined) {
      user.value = undefined;
      void load(id);
    }
  },
  { immediate: true },
);

const title = computed(() => user.value?.name ?? drawer.id.value ?? "User");
const waiting = computed(() => user.value?.invitation !== undefined && user.value.active);

async function resend(asLink: boolean): Promise<void> {
  const current = user.value;
  if (current === undefined) {
    return;
  }
  const confirmed = await confirm({
    title: asLink ? `Get a new link for ${current.name}?` : `Resend the invitation to ${current.name}?`,
    description: asLink
      ? "The link is shown once, to you. The earlier link stops working."
      : `An email goes to ${current.email}. The earlier link stops working.`,
    confirmLabel: asLink ? "Get link" : "Resend",
  });
  if (!confirmed) {
    return;
  }
  busy.value = true;
  try {
    const { data, error, response } = await requireCall(admin, "resendAdminInvitation")({ path: { id: current.id }, body: asLink ? { returnInviteLink: true } : {} });
    if (data === undefined) {
      flash.error(toServiceError(error, response).message);
      return;
    }
    if (asLink && data.inviteLink !== undefined) {
      shownLink.value = { link: data.inviteLink, expiresAt: data.expiresAt };
    } else if (asLink) {
      flash.warning("The platform returned no link. Try again.");
    } else {
      flash.success(`Invitation sent to ${current.email}.`);
    }
    await load(current.id);
    await refreshList();
  } finally {
    busy.value = false;
  }
}

async function revoke(): Promise<void> {
  const current = user.value;
  if (current === undefined) {
    return;
  }
  const confirmed = await confirm({
    title: `Revoke the invitation of ${current.name}?`,
    description: "Their link stops working and the account is deactivated. Someone with access to the platform console can reactivate it.",
    confirmLabel: "Revoke invitation",
    cancelLabel: "Keep invitation",
    color: "error",
  });
  if (!confirmed) {
    return;
  }
  busy.value = true;
  try {
    const { error, response } = await requireCall(admin, "revokeAdminInvitation")({ path: { id: current.id } });
    if (error !== undefined) {
      flash.error(toServiceError(error, response).message);
      return;
    }
    flash.success(`The invitation of ${current.name} is revoked.`);
    await load(current.id);
    await refreshList();
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <FwRecordDrawer :open="true" :title="title" :subtitle="user?.email ?? ''" @close="drawer.close">
    <template #extras>
      <FwStatusBadge v-if="user?.invitation !== undefined" :status="user.invitation" :colors="INVITATION_COLORS" />
    </template>
    <p v-if="loadError !== undefined" class="text-sm text-error" role="alert" data-testid="drawer-error">{{ loadError }}</p>
    <template v-if="user !== undefined">
      <FwFormSection title="User">
        <FwDetailList>
          <FwDetailField label="Name" :value="user.name" />
          <FwDetailField label="Email" :value="user.email" />
          <FwDetailField label="Client" :value="user.clientId" />
          <FwDetailField label="Sign-in" :value="user.idpType === 'OIDC' ? 'Company directory' : 'Password'" />
          <FwDetailField label="Account" :value="user.active ? 'Active' : 'Inactive'" />
          <FwDetailField label="Last signed in"><FwLocalTime :at="user.lastLoginAt" empty="Never" /></FwDetailField>
          <FwDetailField label="Created"><FwLocalTime :at="user.createdAt" /></FwDetailField>
        </FwDetailList>
      </FwFormSection>
      <FwFormSection title="Roles" description="This application's roles that the user holds.">
        <p v-if="user.roles.length === 0" class="text-sm text-muted" data-testid="no-roles">No roles of this application.</p>
        <ul v-else class="space-y-1" data-testid="user-roles">
          <li v-for="role in user.roles" :key="role" class="font-mono text-[13px]">{{ role }}</li>
        </ul>
      </FwFormSection>
      <FwFormSection
        v-if="user.invitation !== undefined"
        title="Invitation"
        :description="user.active ? (user.invitation === 'PENDING' ? 'The invitation has not been used yet.' : 'The invitation lapsed. Send it again.') : 'The invitation is revoked and the account is inactive.'"
      >
        <div v-if="waiting && (canResend || canRevoke)" class="flex flex-wrap gap-2">
          <UButton v-if="canResend" label="Resend by email…" icon="i-lucide-mail" color="neutral" variant="outline" :disabled="busy" data-testid="resend-email" @click="resend(false)" />
          <UButton v-if="canResend" label="Get a new link…" icon="i-lucide-link" color="neutral" variant="outline" :disabled="busy" data-testid="resend-link" @click="resend(true)" />
          <UButton v-if="canRevoke" label="Revoke invitation…" icon="i-lucide-ban" color="error" variant="outline" :disabled="busy" data-testid="revoke" @click="revoke" />
        </div>
      </FwFormSection>
    </template>
    <template #footer>
      <UButton label="Close" color="neutral" variant="ghost" @click="drawer.close" />
    </template>
  </FwRecordDrawer>
  <InviteLinkDialog :link="shownLink?.link" :who="user?.name ?? ''" :expires-at="shownLink?.expiresAt" @close="shownLink = undefined" />
</template>
