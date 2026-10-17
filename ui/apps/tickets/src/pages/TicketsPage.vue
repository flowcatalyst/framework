<script setup lang="ts">
// The tickets list: URL state from useListState (status and "whose" filters), keyset paging from
// useCursorPagination, the record drawer as a child route and a create form in FwFormDialog validated
// by the generated zOpenTicketBody. Every call goes to the Java service through the configured client.
import { ApiRequestError, toServiceError, type ServiceError } from "@flowcatalyst/api-runtime";
import {
  choiceFilter,
  FwCursorPager,
  FwFormDialog,
  FwListPage,
  FwLocalTime,
  FwStatusBadge,
  stringFilter,
  useCursorPagination,
  useFlash,
  useListState,
  usePermissions,
  type FwListColumn,
} from "@flowcatalyst/ui";
import UBadge from "@nuxt/ui/components/Badge.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import UTextarea from "@nuxt/ui/components/Textarea.vue";
import { computed, h, onMounted, provide, reactive, shallowRef } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { listTickets, openTicket } from "../api/tickets/sdk.gen.ts";
import type { OpenTicketBody, TicketSummaryView } from "../api/tickets/types.gen.ts";
import { zOpenTicketBody } from "../api/tickets/zod.gen.ts";
import { OPEN } from "../pages.ts";
import { PRIORITIES, PRIORITY_COLORS, REFRESH_TICKETS, STATUS_COLORS, STATUSES } from "./tickets.ts";

const route = useRoute();
const router = useRouter();
const flash = useFlash();
const permissions = usePermissions();
const canOpen = computed(() => permissions.can(OPEN));
const me = computed(() => permissions.user.value?.principal.id);

const list = useListState({}, () => {
  void pager.reset();
});
const search = list.filter(stringFilter("q", { debounce: 250 }));
const status = list.filter(choiceFilter("status", STATUSES));
const whose = list.filter(choiceFilter("whose", ["assigned", "opened"] as const));

const pager = useCursorPagination<TicketSummaryView>({
  fetchPage: async (cursor) => {
    const { data, error, response } = await listTickets({
      query: {
        limit: 8,
        ...(status.value === null ? {} : { status: status.value }),
        ...(whose.value === "assigned" && me.value !== undefined ? { assignee: me.value } : {}),
        ...(whose.value === "opened" && me.value !== undefined ? { requester: me.value } : {}),
        ...(cursor === undefined ? {} : { after: cursor }),
      },
    });
    if (data === undefined) {
      throw new ApiRequestError(toServiceError(error, response));
    }
    return data;
  },
  // A 401 is already on its way to sign-in and a 403 or 5xx is a toast of the runtime; the list says
  // what it could not do in its own words as well.
  onError: (failure) => {
    flash.error(failure instanceof ApiRequestError ? `Could not load the tickets: ${failure.message}` : "Could not load the tickets.");
  },
});
provide(REFRESH_TICKETS, () => pager.refresh());
onMounted(() => {
  void pager.loadFirst();
});

const selected = computed(() => {
  const id = route.params["id"];
  return typeof id === "string" ? id : undefined;
});

// Below the sm breakpoint a phone shows Ticket, Subject and Status (FwListPage drops `phone: "hide"`
// columns); the rest is in the drawer.
const columns: FwListColumn<TicketSummaryView>[] = [
  {
    accessorKey: "id",
    header: "Ticket",
    cell: ({ row }) =>
      h(RouterLink, { to: { path: `/tickets/${row.original.id}`, query: route.query }, class: "font-medium text-highlighted underline-offset-2 hover:underline" }, () => row.original.id),
  },
  { accessorKey: "subject", header: "Subject" },
  { accessorKey: "priority", header: "Priority", phone: "hide", cell: ({ row }) => h(FwStatusBadge, { status: row.original.priority, colors: PRIORITY_COLORS }) },
  {
    accessorKey: "status",
    header: "Status",
    cell: ({ row }) =>
      h("span", { class: "inline-flex items-center gap-2" }, [
        h(FwStatusBadge, { status: row.original.status, colors: STATUS_COLORS }),
        row.original.overdue ? h(UBadge, { label: "Overdue", color: "error", variant: "outline", "data-testid": "overdue-badge" }) : null,
      ]),
  },
  { accessorKey: "assigneeId", header: "Assignee", phone: "hide", cell: ({ row }) => row.original.assigneeId ?? "-" },
  { accessorKey: "dueAt", header: "Due", phone: "hide", cell: ({ row }) => h(FwLocalTime, { at: row.original.dueAt }) },
];

const statusItems = [{ label: "Any status", value: "any" }, ...STATUSES.map((s) => ({ label: s === "IN_PROGRESS" ? "In progress" : s.charAt(0) + s.slice(1).toLowerCase(), value: s }))];
const statusChoice = computed({
  get: () => status.value ?? "any",
  set: (next: string) => {
    status.value = STATUSES.find((s) => s === next) ?? null;
  },
});
const whoseItems = [
  { label: "Everyone's", value: "any" },
  { label: "Assigned to me", value: "assigned" },
  { label: "Opened by me", value: "opened" },
];
const whoseChoice = computed({
  get: () => whose.value ?? "any",
  set: (next: string) => {
    whose.value = next === "assigned" || next === "opened" ? next : null;
  },
});

function open(row: TicketSummaryView): void {
  void router.push({ path: `/tickets/${row.id}`, query: route.query });
}

// New ticket.
const creating = shallowRef(false);
const draft = reactive<OpenTicketBody>({ subject: "", description: "", priority: "NORMAL" });
const priorityItems = PRIORITIES.map((p) => ({ label: p.charAt(0) + p.slice(1).toLowerCase(), value: p }));
function startCreate(): void {
  Object.assign(draft, { subject: "", description: "", priority: "NORMAL" });
  creating.value = true;
}
async function create(body: OpenTicketBody): Promise<ServiceError | undefined> {
  const { data, error, response } = await openTicket({ body });
  if (data === undefined) {
    return toServiceError(error, response);
  }
  flash.success(`Ticket ${data.id} opened.`);
  await pager.reset();
  await router.push({ path: `/tickets/${data.id}`, query: route.query });
  return undefined;
}
</script>

<template>
  <FwListPage
    v-model:search="search"
    title="Tickets"
    subtitle="Support tickets of your client, newest first"
    :data="pager.items.value"
    :columns="columns"
    :loading="pager.loading.value"
    :active-filter-count="(status === null ? 0 : 1) + (whose === null ? 0 : 1)"
    :row-key="(row: TicketSummaryView) => row.id"
    :selected-key="selected"
    empty-message="No tickets match."
    search-placeholder="Search this page…"
    search-loaded-rows
    @select="open"
    @refresh="pager.refresh"
    @clear="list.clearFilters"
  >
    <template #actions>
      <UButton v-if="canOpen" label="New ticket" icon="i-lucide-plus" data-testid="new-ticket" @click="startCreate" />
    </template>
    <template #filters>
      <UFormField label="Status">
        <USelect v-model="statusChoice" :items="statusItems" class="w-full" data-testid="filter-status" />
      </UFormField>
      <UFormField label="Whose">
        <USelect v-model="whoseChoice" :items="whoseItems" class="w-full" data-testid="filter-whose" />
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
    v-model:open="creating"
    title="New ticket"
    description="What is wrong, and how soon it needs attention. Your client's SLA sets the deadline."
    submit-label="Open ticket"
    :schema="zOpenTicketBody"
    :state="draft"
    :submit="create"
  >
    <UFormField label="Subject" name="subject" required>
      <UInput v-model="draft.subject" class="w-full" data-testid="new-subject" />
    </UFormField>
    <UFormField label="Description" name="description">
      <UTextarea v-model="draft.description" class="w-full" :rows="4" data-testid="new-description" />
    </UFormField>
    <UFormField label="Priority" name="priority" required>
      <USelect v-model="draft.priority" :items="priorityItems" class="w-full" data-testid="new-priority" />
    </UFormField>
  </FwFormDialog>
</template>
