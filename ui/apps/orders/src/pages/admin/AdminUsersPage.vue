<script setup lang="ts">
// Users of this application and their invitations (M4e slice 1): the people who hold this
// application's roles, over the generated listAdminUsers / listAdminInvitations, with the record
// drawer as a child route and the invite form in FwFormDialog. The server pages by cursor and
// confines everything to this application; the platform's refusals show on the page in its words.
import { toServiceError, type ServiceError } from "@flowcatalyst/api-runtime";
import {
  choiceFilter,
  FwCursorPager,
  FwFormDialog,
  FwListPage,
  FwPickList,
  FwStatusBadge,
  stringFilter,
  useCursorPagination,
  useFlash,
  useListState,
  usePermissions,
  type FwListColumn,
  type PickItem,
} from "@flowcatalyst/ui";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import USwitch from "@nuxt/ui/components/Switch.vue";
import { computed, h, onMounted, provide, reactive, shallowRef } from "vue";
import type { z } from "zod";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { inviteAdminUser, listAdminInvitations, listAdminRoles, listAdminUsers } from "../../api/orders/sdk.gen.ts";
import { zInviteBody } from "../../api/orders/zod.gen.ts";
import { ADMIN_USERS_INVITE } from "../../admin/icons.ts";
import InviteLinkDialog from "./InviteLinkDialog.vue";
import { dataOrThrow, failureText, INVITATION_COLORS, REFRESH_USERS } from "./platform.ts";

type InviteInput = z.input<typeof zInviteBody>;
type InviteData = z.output<typeof zInviteBody>;

/** One row, whichever list it came from. */
interface Row {
  id: string;
  name: string;
  email: string;
  clientId: string;
  roles: string[];
  invitation?: string | undefined;
  active?: boolean | undefined;
}

const route = useRoute();
const router = useRouter();
const flash = useFlash();
const permissions = usePermissions();
const canInvite = computed(() => permissions.can(ADMIN_USERS_INVITE));
const failure = shallowRef<string | undefined>(undefined);

const list = useListState({}, () => {
  void pager.reset();
});
const search = list.filter(stringFilter("q", { debounce: 250 }));
const show = list.filter(choiceFilter("show", ["PENDING", "EXPIRED", "INACTIVE"] as const));

const pager = useCursorPagination<Row>({
  fetchPage: async (cursor) => {
    const after = cursor === undefined ? {} : { after: cursor };
    // Invitations still waiting: their own list (the platform filters by state); everyone else: the users.
    if (show.value === "PENDING" || show.value === "EXPIRED") {
      const page = dataOrThrow(await listAdminInvitations({ query: { limit: 8, status: show.value, ...after } }));
      failure.value = undefined;
      // The platform does not search invitations: the page that came back is filtered here.
      const needle = search.value.trim().toLowerCase();
      return {
        ...(page.nextCursor === undefined ? {} : { nextCursor: page.nextCursor }),
        items: page.items
          .filter((i) => needle === "" || `${i.name} ${i.email}`.toLowerCase().includes(needle))
          .map((i) => ({ id: i.userId, name: i.name, email: i.email, clientId: i.clientId, roles: i.roles, invitation: i.status })),
      };
    }
    const page = dataOrThrow(
      await listAdminUsers({
        query: { limit: 8, ...(show.value === "INACTIVE" ? { active: false } : {}), ...(search.value === "" ? {} : { q: search.value }), ...after },
      }),
    );
    failure.value = undefined;
    return {
      ...(page.nextCursor === undefined ? {} : { nextCursor: page.nextCursor }),
      items: page.items.map((u) => ({ id: u.id, name: u.name, email: u.email, clientId: u.clientId, roles: u.roles, invitation: u.invitation, active: u.active })),
    };
  },
  onError: (cause) => {
    failure.value = failureText(cause, "The users");
  },
});
provide(REFRESH_USERS, () => pager.refresh());
onMounted(() => {
  void pager.loadFirst();
});

const selected = computed(() => {
  const id = route.params["id"];
  return typeof id === "string" ? id : undefined;
});

const columns: FwListColumn<Row>[] = [
  {
    accessorKey: "name",
    header: "Name",
    cell: ({ row }) =>
      h(
        RouterLink,
        { to: { path: `/admin/users/${row.original.id}`, query: route.query }, class: "font-medium text-highlighted underline-offset-2 hover:underline" },
        () => row.original.name,
      ),
  },
  { accessorKey: "email", header: "Email", phone: "hide" },
  { accessorKey: "roles", header: "Roles", phone: "hide", cell: ({ row }) => row.original.roles.map((r) => r.slice(r.indexOf(":") + 1)).join(", ") },
  {
    accessorKey: "invitation",
    header: "Invitation",
    cell: ({ row }) =>
      row.original.invitation === undefined
        ? row.original.active === false
          ? "Inactive"
          : ""
        : h(FwStatusBadge, { status: row.original.invitation, colors: INVITATION_COLORS }),
  },
];

const showItems = [
  { label: "Everyone", value: "any" },
  { label: "Invitation pending", value: "PENDING" },
  { label: "Invitation expired", value: "EXPIRED" },
  { label: "Inactive", value: "INACTIVE" },
];
// The switch wants a boolean, the schema allows it to be left out.
const asLink = computed({
  get: () => draft.returnInviteLink === true,
  set: (next: boolean) => {
    draft.returnInviteLink = next;
  },
});
const showChoice = computed({
  get: () => show.value ?? "any",
  set: (next: string) => {
    show.value = next === "PENDING" || next === "EXPIRED" || next === "INACTIVE" ? next : null;
  },
});

function open(row: Row): void {
  void router.push({ path: `/admin/users/${row.id}`, query: route.query });
}

// Invite a user.
const inviting = shallowRef(false);
const draft = reactive<InviteInput>({ email: "", name: "", clientId: "", roles: [], returnInviteLink: false });
const roleItems = shallowRef<PickItem[]>([]);
const rolesError = shallowRef<string | undefined>(undefined);
// The link the platform returned, shown once; dropped when the dialog is closed.
const shownLink = shallowRef<{ link: string; who: string } | undefined>(undefined);

async function startInvite(): Promise<void> {
  Object.assign(draft, { email: "", name: "", clientId: "", roles: [], returnInviteLink: false });
  rolesError.value = undefined;
  inviting.value = true;
  try {
    const page = dataOrThrow(await listAdminRoles({ query: { limit: 200 } }));
    roleItems.value = page.items.map((role) => ({ value: role.name, label: role.displayName, code: role.name }));
  } catch (cause) {
    roleItems.value = [];
    rolesError.value = failureText(cause, "The roles");
  }
}

async function invite(data: InviteData): Promise<ServiceError | undefined> {
  const wantsLink = data.returnInviteLink === true;
  const { data: made, error, response } = await inviteAdminUser({ body: { ...data, returnInviteLink: wantsLink, sendInvitation: !wantsLink } });
  if (made === undefined) {
    return toServiceError(error, response);
  }
  if (wantsLink && made.inviteLink !== undefined) {
    shownLink.value = { link: made.inviteLink, who: data.name };
  } else if (wantsLink) {
    flash.warning(`${data.name} was added, but the platform returned no link. Resend the invitation from the user.`);
  } else if (made.invitationSent) {
    flash.success(`Invitation sent to ${data.email}.`);
  } else {
    flash.warning(`${data.name} was added, but no email was sent (the account may sign in through its company directory). Resend the invitation from the user if it should be.`);
  }
  await pager.reset();
  return undefined;
}
</script>

<template>
  <div class="space-y-4">
    <UAlert
      v-if="failure !== undefined"
      color="error"
      variant="subtle"
      title="The platform could not be read"
      :description="failure"
      data-testid="platform-error"
    />
    <FwListPage
      v-model:search="search"
      title="Users"
      subtitle="The people who hold this application's roles, and their invitations"
      :data="pager.items.value"
      :columns="columns"
      :loading="pager.loading.value"
      :active-filter-count="show === null ? 0 : 1"
      :row-key="(row: Row) => row.id"
      :selected-key="selected"
      empty-message="No users match."
      search-placeholder="Search name or email…"
      @select="open"
      @refresh="pager.refresh"
      @clear="list.clearFilters"
    >
      <template #actions>
        <UButton v-if="canInvite" label="Invite user" icon="i-lucide-user-plus" data-testid="invite-user" @click="startInvite" />
      </template>
      <template #filters>
        <UFormField label="Show">
          <USelect v-model="showChoice" :items="showItems" class="w-full" data-testid="filter-show" />
        </UFormField>
      </template>
      <template #pager>
        <FwCursorPager
          :summary="`Page ${String(pager.page.value)}`"
          :has-prev="pager.hasPrev.value"
          :has-more="pager.hasMore.value"
          :loading="pager.loading.value"
          @newest="pager.loadFirst"
          @newer="pager.loadPrev"
          @older="pager.loadNext"
        />
      </template>
      <template #drawer>
        <RouterView />
      </template>
    </FwListPage>
    <FwFormDialog
      v-model:open="inviting"
      title="Invite a user"
      description="They get this application's roles at the client you name, and set their own password from the invitation."
      submit-label="Send invitation"
      :schema="zInviteBody"
      :state="draft"
      :submit="invite"
    >
      <UFormField label="Email" name="email" required>
        <UInput v-model="draft.email" type="email" autocomplete="off" class="w-full" data-testid="invite-email" />
      </UFormField>
      <UFormField label="Name" name="name" required>
        <UInput v-model="draft.name" autocomplete="off" class="w-full" data-testid="invite-name" />
      </UFormField>
      <UFormField label="Client" name="clientId" required help="The client's identifier, such as acme, or its id.">
        <UInput v-model="draft.clientId" autocomplete="off" class="w-full" data-testid="invite-client" />
      </UFormField>
      <UFormField name="roles">
        <div role="group" aria-labelledby="invite-roles-label">
        <p id="invite-roles-label" class="mb-1.5 text-sm font-medium text-highlighted">Roles <span class="text-error" aria-hidden="true">*</span></p>
        <p v-if="rolesError !== undefined" class="text-sm text-error" role="alert" data-testid="invite-roles-error">{{ rolesError }}</p>
        <FwPickList v-model="draft.roles" :items="roleItems" available-title="This application's roles" selected-title="Given" filter-label="Filter roles" empty-text="No role yet" />
        </div>
      </UFormField>
      <UFormField name="returnInviteLink">
        <USwitch v-model="asLink" label="Show me the link instead of emailing it" description="The link is shown once, in the next window. Nothing is emailed." data-testid="invite-as-link" />
      </UFormField>
    </FwFormDialog>
    <InviteLinkDialog :link="shownLink?.link" :who="shownLink?.who ?? ''" @close="shownLink = undefined" />
  </div>
</template>
