<script setup lang="ts">
// The application's own events and the dispatch jobs that deliver them (M4e slice 4), read-only: one
// list with two views (Events, Dispatch jobs) over the generated listAdminEvents / listAdminDispatchJobs,
// each with its drawer as a child route. The server confines both to this application and pages them by
// cursor; the platform's refusals show on the page in its words.
import FwCodeChips from "../../components/FwCodeChips.vue";
import FwCursorPager from "../../components/FwCursorPager.vue";
import FwListPage from "../../partials/FwListPage.vue";
import FwLocalTime from "../../components/FwLocalTime.vue";
import FwStatusBadge from "../../components/FwStatusBadge.vue";
import FwToggleGroup from "../../components/FwToggleGroup.vue";
import { choiceFilter, stringFilter, useListState } from "../../composables/useListState.ts";
import { useCursorPagination } from "../../composables/useCursorPagination.ts";
import type { FwListColumn } from "../../types.ts";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import { computed, h, onMounted, shallowRef } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { requireCall, useAdminData } from "../data.ts";
import type { AdminDispatchJobView as AdminDispatchJob, AdminEventView as AdminEvent } from "../data.ts";
import { dataOrThrow, failureText, JOB_COLORS } from "../shared.ts";

type Row = (AdminEvent & { kind: "event" }) | (AdminDispatchJob & { kind: "job" });

const admin = useAdminData();
const JOB_STATUSES = ["PENDING", "QUEUED", "IN_PROGRESS", "COMPLETED", "FAILED", "CANCELLED"] as const;

const route = useRoute();
const router = useRouter();
const failure = shallowRef<string | undefined>(undefined);

const list = useListState({}, () => {
  void pager.reset();
});
const search = list.filter(stringFilter("q"));
const view = list.filter(choiceFilter("view", ["jobs"] as const));
const type = list.filter(stringFilter("type", { debounce: 250 }));
const correlation = list.filter(stringFilter("correlationId", { debounce: 250 }));
const status = list.filter(choiceFilter("status", JOB_STATUSES));
const code = list.filter(stringFilter("code", { debounce: 250 }));
const since = list.filter(stringFilter("since"));

const jobs = computed(() => view.value === "jobs");

/** A local date and time from the input as the instant the server wants, or nothing if it is not one. */
function instant(local: string): string | undefined {
  const at = new Date(local);
  return local === "" || Number.isNaN(at.getTime()) ? undefined : at.toISOString();
}

const pager = useCursorPagination<Row>({
  fetchPage: async (cursor) => {
    const sinceAt = instant(since.value);
    const common = { limit: admin.pageSize, ...(cursor === undefined ? {} : { after: cursor }), ...(sinceAt === undefined ? {} : { since: sinceAt }) };
    const next = (token: string | undefined): { nextCursor?: string } => (token === undefined ? {} : { nextCursor: token });
    if (jobs.value) {
      const page = dataOrThrow(
        await requireCall(admin, "listAdminDispatchJobs")({
          query: { ...common, ...(status.value === null ? {} : { status: status.value }), ...(code.value === "" ? {} : { code: code.value }) },
        }),
      );
      failure.value = undefined;
      return { ...next(page.nextCursor), items: page.items.map((j): Row => ({ ...j, kind: "job" })) };
    }
    const page = dataOrThrow(
      await requireCall(admin, "listAdminEvents")({
        query: { ...common, ...(type.value === "" ? {} : { type: type.value }), ...(correlation.value === "" ? {} : { correlationId: correlation.value }) },
      }),
    );
    failure.value = undefined;
    return { ...next(page.nextCursor), items: page.items.map((e): Row => ({ ...e, kind: "event" })) };
  },
  onError: (cause) => {
    failure.value = failureText(cause, "The activity");
  },
});
onMounted(() => {
  void pager.loadFirst();
});

const selected = computed(() => {
  const id = route.params["id"];
  return typeof id === "string" ? id : undefined;
});

function drawerPath(row: Row): string {
  return row.kind === "event" ? `/admin/activity/events/${row.id}` : `/admin/activity/jobs/${row.id}`;
}

const eventColumns: FwListColumn<Row>[] = [
  {
    accessorKey: "type",
    header: "Event",
    cell: ({ row }) =>
      h(RouterLink, { to: { path: drawerPath(row.original), query: route.query }, class: "underline-offset-2 hover:underline" }, () =>
        h(FwCodeChips, { code: row.original.kind === "event" ? row.original.type : row.original.code }),
      ),
  },
  { id: "subject", header: "Subject", phone: "hide", cell: ({ row }) => (row.original.kind === "event" ? (row.original.subject ?? "") : "") },
  { id: "time", header: "When", cell: ({ row }) => h(FwLocalTime, { at: row.original.kind === "event" ? row.original.time : row.original.createdAt }) },
];

const jobColumns: FwListColumn<Row>[] = [
  {
    accessorKey: "code",
    header: "Dispatch job",
    cell: ({ row }) =>
      h(RouterLink, { to: { path: drawerPath(row.original), query: route.query }, class: "underline-offset-2 hover:underline" }, () =>
        h(FwCodeChips, { code: row.original.kind === "job" ? row.original.code : row.original.type }),
      ),
  },
  { accessorKey: "status", header: "State", cell: ({ row }) => (row.original.kind === "job" ? h(FwStatusBadge, { status: row.original.status, colors: JOB_COLORS }) : "") },
  { id: "target", header: "Delivered to", phone: "hide", cell: ({ row }) => (row.original.kind === "job" ? row.original.targetUrl : "") },
  { id: "time", header: "Created", phone: "hide", cell: ({ row }) => h(FwLocalTime, { at: row.original.kind === "event" ? row.original.time : row.original.createdAt }) },
];

const columns = computed(() => (jobs.value ? jobColumns : eventColumns));

const viewModel = computed({
  get: () => [jobs.value ? "jobs" : "events"],
  set: (next: string[]) => {
    // Another view is another list: the filters of the one left do not apply to the one entered.
    list.clearFilters();
    view.value = next[0] === "jobs" ? "jobs" : null;
  },
});
const views = [
  { label: "Events", value: "events" },
  { label: "Dispatch jobs", value: "jobs" },
];

const statusItems = [{ label: "Any state", value: "any" }, ...JOB_STATUSES.map((s) => ({ label: s.replace("_", " ").toLowerCase().replace(/^./, (c) => c.toUpperCase()), value: s }))];
const statusChoice = computed({
  get: () => status.value ?? "any",
  set: (next: string) => {
    status.value = JOB_STATUSES.find((s) => s === next) ?? null;
  },
});

const filterCount = computed(() =>
  [since.value !== "", ...(jobs.value ? [status.value !== null, code.value !== ""] : [type.value !== "", correlation.value !== ""])].filter(Boolean).length,
);

function open(row: Row): void {
  void router.push({ path: drawerPath(row), query: route.query });
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
      title="Activity"
      subtitle="This application's events and the dispatch jobs that deliver them"
      :data="pager.items.value"
      :columns="columns"
      :loading="pager.loading.value"
      :active-filter-count="filterCount"
      :row-key="(row: Row) => row.id"
      :selected-key="selected"
      :empty-message="jobs ? 'No dispatch jobs match.' : 'No events match.'"
      search-placeholder="Search this page…"
      search-loaded-rows
      @select="open"
      @refresh="pager.refresh"
      @clear="list.clearFilters"
    >
      <template #actions>
        <FwToggleGroup v-model="viewModel" :options="views" label="Show" kind="exclusive" />
      </template>
      <template #filters>
        <template v-if="jobs">
          <UFormField label="State">
            <USelect v-model="statusChoice" :items="statusItems" class="w-full" data-testid="filter-status" />
          </UFormField>
          <UFormField label="Job code (exact)">
            <UInput v-model="code" class="w-full" :placeholder="`${admin.application}:domain:aggregate:event`" data-testid="filter-code" />
          </UFormField>
        </template>
        <template v-else>
          <UFormField label="Event type (exact)">
            <UInput v-model="type" class="w-full" :placeholder="`${admin.application}:domain:aggregate:event`" data-testid="filter-type" />
          </UFormField>
          <UFormField label="Correlation id">
            <UInput v-model="correlation" class="w-full" data-testid="filter-correlation" />
          </UFormField>
        </template>
        <UFormField label="Since">
          <UInput v-model="since" type="datetime-local" class="w-full" data-testid="filter-since" />
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
  </div>
</template>
