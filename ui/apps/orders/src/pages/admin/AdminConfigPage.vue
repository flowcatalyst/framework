<script setup lang="ts">
// The application's configuration and secret status (M4e slice 3): the declared settings with where
// each value came from, summary counts and the application's health. A secret is shown as set or not,
// never its value; the page says so. A plain list (the response is small and not paged): the search and
// the state filter narrow the loaded rows through the server's q and state parameters.
import { choiceFilter, FwListPage, FwStatusBadge, stringFilter, useListState, type FwListColumn, type StatusColor } from "@flowcatalyst/ui";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import { computed, h, onMounted, shallowRef } from "vue";
import { listAdminConfig } from "../../api/orders/sdk.gen.ts";
import type { AdminConfig, AdminConfigSetting } from "../../api/orders/types.gen.ts";
import { dataOrThrow, failureText } from "./platform.ts";

const STATE_COLORS: Readonly<Record<string, StatusColor>> = { SET: "success", DEFAULT: "info", UNSET: "neutral" };
const STATE_LABELS: Readonly<Record<string, string>> = { SET: "Set", DEFAULT: "Default", UNSET: "Not set" };
const HEALTH_COLORS: Readonly<Record<string, StatusColor>> = { UP: "success", DOWN: "error", TIMED_OUT: "warning" };

const config = shallowRef<AdminConfig | undefined>(undefined);
const loading = shallowRef(false);
const failure = shallowRef<string | undefined>(undefined);

const list = useListState({}, () => {
  void load();
});
const search = list.filter(stringFilter("q", { debounce: 250 }));
const state = list.filter(choiceFilter("state", ["SET", "DEFAULT", "UNSET"] as const));

async function load(): Promise<void> {
  loading.value = true;
  try {
    config.value = dataOrThrow(
      await listAdminConfig({
        query: {
          ...(state.value === null ? {} : { state: state.value }),
          ...(search.value === "" ? {} : { q: search.value }),
        },
      }),
    );
    failure.value = undefined;
  } catch (cause) {
    failure.value = failureText(cause, "The configuration");
  } finally {
    loading.value = false;
  }
}
onMounted(() => {
  void load();
});

const columns: FwListColumn<AdminConfigSetting>[] = [
  { accessorKey: "key", header: "Key", cell: ({ row }) => h("span", { class: "font-mono text-[13px] text-highlighted break-all" }, row.original.key) },
  {
    accessorKey: "state",
    header: "State",
    cell: ({ row }) =>
      h(FwStatusBadge, { status: row.original.state, colors: STATE_COLORS, label: row.original.secret && row.original.state === "SET" ? "Set (secret)" : (STATE_LABELS[row.original.state] ?? row.original.state) }),
  },
  {
    accessorKey: "value",
    header: "Value",
    phone: "hide",
    cell: ({ row }) =>
      row.original.value !== undefined
        ? h("span", { class: "font-mono text-[13px] break-all" }, row.original.value)
        : h("span", { class: "text-muted" }, row.original.withheld ? "hidden" : "-"),
  },
  { accessorKey: "source", header: "Source", phone: "hide" },
  { accessorKey: "module", header: "Module", phone: "hide" },
];

const stateItems = [
  { label: "Any state", value: "any" },
  { label: "Set", value: "SET" },
  { label: "Default", value: "DEFAULT" },
  { label: "Not set", value: "UNSET" },
];
const stateChoice = computed({
  get: () => state.value ?? "any",
  set: (next: string) => {
    state.value = next === "SET" || next === "DEFAULT" || next === "UNSET" ? next : null;
  },
});
</script>

<template>
  <div class="space-y-4">
    <UAlert v-if="failure !== undefined" color="error" variant="subtle" title="Could not load" :description="failure" data-testid="config-error" />
    <section v-if="config !== undefined" aria-labelledby="config-summary" class="flex flex-wrap items-center gap-x-6 gap-y-2" data-testid="config-summary">
      <h2 id="config-summary" class="sr-only">Summary</h2>
      <p class="text-sm"><span class="font-medium text-highlighted">{{ config.set }}</span> set</p>
      <p class="text-sm"><span class="font-medium text-highlighted">{{ config.defaulted }}</span> using their default</p>
      <p class="text-sm"><span class="font-medium text-highlighted">{{ config.unset }}</span> not set</p>
      <p class="text-sm text-muted">Environment: {{ config.environment }}</p>
      <p class="flex items-center gap-2 text-sm" data-testid="config-health">
        Health
        <FwStatusBadge :status="config.health.ready ? 'UP' : 'DOWN'" :colors="HEALTH_COLORS" :label="config.health.ready ? 'Ready' : 'Not ready'" />
      </p>
    </section>
    <FwListPage
      v-model:search="search"
      title="Configuration"
      subtitle="Settings the application runs with and where each value came from. Secrets are never shown."
      :data="config?.settings ?? []"
      :columns="columns"
      :loading="loading"
      :active-filter-count="state === null ? 0 : 1"
      :row-key="(row: AdminConfigSetting) => row.key"
      empty-message="No settings match."
      search-placeholder="Search key or module…"
      @refresh="load"
      @clear="list.clearFilters"
    >
      <template #filters>
        <UFormField label="State">
          <USelect v-model="stateChoice" :items="stateItems" class="w-full" data-testid="filter-state" />
        </UFormField>
      </template>
    </FwListPage>
  </div>
</template>
