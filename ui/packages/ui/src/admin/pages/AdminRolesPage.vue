<script setup lang="ts">
// Roles of this application, as the platform holds them (M4e slice 2): a read-only list over the
// generated listAdminRoles with the record drawer as a child route. The server confines the list to
// this application's roles and pages it by cursor.
import FwCursorPager from "../../components/FwCursorPager.vue";
import FwListPage from "../../partials/FwListPage.vue";
import { stringFilter, useListState } from "../../composables/useListState.ts";
import { useCursorPagination } from "../../composables/useCursorPagination.ts";
import type { FwListColumn } from "../../types.ts";
import UAlert from "@nuxt/ui/components/Alert.vue";
import { computed, h, onMounted, shallowRef } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { requireCall, useAdminData } from "../data.ts";
import type { AdminRoleView as AdminRole } from "../data.ts";
import { dataOrThrow, failureText } from "../shared.ts";

const admin = useAdminData();
const route = useRoute();
const router = useRouter();
const failure = shallowRef<string | undefined>(undefined);

const list = useListState({}, () => {
  void pager.reset();
});
const search = list.filter(stringFilter("q", { debounce: 250 }));

const pager = useCursorPagination<AdminRole>({
  fetchPage: async (cursor) => {
    const page = dataOrThrow(
      await requireCall(admin, "listAdminRoles")({
        query: {
          limit: admin.pageSize,
          ...(search.value === "" ? {} : { q: search.value }),
          ...(cursor === undefined ? {} : { after: cursor }),
        },
      }),
    );
    failure.value = undefined;
    return page;
  },
  onError: (cause) => {
    failure.value = failureText(cause, "The roles");
  },
});
onMounted(() => {
  void pager.loadFirst();
});

const selected = computed(() => {
  const id = route.params["id"];
  return typeof id === "string" ? id : undefined;
});

const columns: FwListColumn<AdminRole>[] = [
  {
    accessorKey: "name",
    header: "Role",
    cell: ({ row }) =>
      h(
        RouterLink,
        { to: { path: `/admin/roles/${row.original.name}`, query: route.query }, class: "font-mono text-[13px] text-highlighted underline-offset-2 hover:underline" },
        () => row.original.name,
      ),
  },
  { accessorKey: "displayName", header: "Name", phone: "hide" },
  { accessorKey: "source", header: "Source", phone: "hide" },
  { accessorKey: "permissionCount", header: "Permissions", meta: { class: { td: "text-right tabular-nums", th: "text-right" } } },
];

function open(row: AdminRole): void {
  void router.push({ path: `/admin/roles/${row.name}`, query: route.query });
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
      title="Roles"
      subtitle="This application's roles and the permissions each grants, as the platform holds them"
      :data="pager.items.value"
      :columns="columns"
      :loading="pager.loading.value"
      :row-key="(row: AdminRole) => row.name"
      :selected-key="selected"
      empty-message="No roles match."
      search-placeholder="Search name or description…"
      @select="open"
      @refresh="pager.refresh"
      @clear="list.clearFilters"
    >
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
