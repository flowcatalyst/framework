<script setup lang="ts">
// What the platform holds for this application (platform config contract section 5): named values by
// section and property, global or per client, plain or secret. A secret shows a lock and bullets, never
// a value; the form never asks for it back. Add, change and delete need `manage`; reading a secret needs
// `reveal`, asks first (it is recorded) and shows the value in a dialog that forgets it on close. A plain
// list (not paged): the search and the filters narrow the rows already loaded.
//
// Clients (contract section 4, Validation): with `{app}:admin:platform-config:all-clients` the user names
// any client; without it only the global values and the session's own client, which the page sends as the
// session's client identifier (what the admin module accepts before it has learned the stored id). The
// list then holds global rows and the own client's rows the module can recognise. A `403 CLIENT_FORBIDDEN`
// still says why, and the platform's `SECRET_VALUE_MASKED` / `SECRET_VALUE_REQUIRED` land on the value.
import { toServiceError, type ServiceError } from "@flowcatalyst/api-runtime";
import FwListPage from "../../partials/FwListPage.vue";
import FwLocalTime from "../../components/FwLocalTime.vue";
import FwRowActions from "../../components/FwRowActions.vue";
import { choiceFilter, stringFilter, useListState } from "../../composables/useListState.ts";
import { useConfirm } from "../../composables/useConfirm.ts";
import { useFlash } from "../../composables/useFlash.ts";
import { usePermissions } from "../../composables/useSession.ts";
import type { FwListColumn, RowAction } from "../../types.ts";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import { computed, h, onMounted, shallowRef } from "vue";
import type { z } from "zod";
import { requireCall, useAdminData } from "../data.ts";
import type { platformConfigFormSchema, AdminPlatformConfigItemView as Item } from "../data.ts";
import { dataOrThrow, failureText } from "../shared.ts";
import PlatformConfigDialog from "./PlatformConfigDialog.vue";
import PlatformConfigRevealDialog from "./PlatformConfigRevealDialog.vue";

type FormData = z.output<typeof platformConfigFormSchema>;

const admin = useAdminData();
const flash = useFlash();
const confirm = useConfirm();
const permissions = usePermissions();
const canManage = computed(() => permissions.can(admin.permissions.platformConfigManage));
const canReveal = computed(() => permissions.can(admin.permissions.platformConfigReveal));
const allClients = computed(() => permissions.can(admin.permissions.platformConfigAllClients));
/** The session's own client identifier; undefined for a session for no particular client (`_platform`). */
const ownClient = computed(() => {
  const client = permissions.user.value?.client;
  return client === undefined || client === "" || client === "_platform" ? undefined : client;
});

/**
 * The `clientId` a call sends for a row's or a form's client. Without all-clients every client the page
 * offers is the own one, sent as the session's identifier (a row carries the stored id, which the module
 * matches only once it has learned the pair).
 */
function requestClient(reference: string | undefined): string | undefined {
  const text = reference?.trim() ?? "";
  if (text === "") {
    return undefined;
  }
  return allClients.value ? text : (ownClient.value ?? text);
}

function clientQuery(reference: string | undefined): { query?: { clientId: string } } {
  const clientId = requestClient(reference);
  return clientId === undefined ? {} : { query: { clientId } };
}

/** How a row's client reads: its stored id, or (without all-clients, where it is the own client) the identifier. */
function clientLabel(clientId: string): string {
  return allClients.value || ownClient.value === undefined ? clientId : ownClient.value;
}

const confinement = computed(() =>
  ownClient.value === undefined
    ? `You are signed in for no particular client, so you see and manage global values only. Client values need the permission ${admin.permissions.platformConfigAllClients}.`
    : `You see and manage the global values and those of your client, ${ownClient.value}. A value of your client is listed once this application has saved or revealed a value for it since it last started. Other clients need the permission ${admin.permissions.platformConfigAllClients}.`,
);

/** A failed call in the page's words: the platform's secret-value refusals go on the value field, a client refusal says why. */
function explained(error: ServiceError): ServiceError {
  switch (error.error) {
    case "SECRET_VALUE_MASKED":
      return { ...error, status: 400, details: { ...error.details, value: "SECRET_VALUE_MASKED: *** is how the platform hides a secret, not a value: enter the secret itself" } };
    case "SECRET_VALUE_REQUIRED":
      return { ...error, status: 400, details: { ...error.details, value: "SECRET_VALUE_REQUIRED: A secret needs a value" } };
    case "CLIENT_FORBIDDEN":
      return { ...error, message: `You may not act for that client. ${confinement.value}` };
    default:
      return error;
  }
}

const items = shallowRef<Item[]>([]);
const loading = shallowRef(false);
const failure = shallowRef<string | undefined>(undefined);

// The filters narrow the loaded rows here; nothing is asked of the server again.
const list = useListState({}, () => undefined);
const search = list.filter(stringFilter("q", { debounce: 250 }));
const section = list.filter(stringFilter("section"));
const scope = list.filter(choiceFilter("scope", ["global", "client"] as const));

async function load(): Promise<void> {
  loading.value = true;
  try {
    items.value = dataOrThrow(await requireCall(admin, "listAdminPlatformConfig")({})).items;
    failure.value = undefined;
  } catch (cause) {
    failure.value = failureText(cause, "The platform config");
  } finally {
    loading.value = false;
  }
}
onMounted(() => {
  void load();
});

const shown = computed(() =>
  items.value.filter(
    (i) =>
      (section.value === "" || i.section === section.value) &&
      (scope.value === null || (scope.value === "global") === (i.clientId === undefined)),
  ),
);

function nameOf(i: Item): string {
  return `${i.section}/${i.property}${i.clientId === undefined ? "" : ` for ${clientLabel(i.clientId)}`}`;
}

function actionsOf(i: Item): RowAction[] {
  return [
    ...(canReveal.value && i.valueType === "SECRET" ? [{ label: "Reveal", icon: "i-lucide-eye", onSelect: () => void reveal(i) }] : []),
    ...(canManage.value
      ? [
          { label: "Edit", icon: "i-lucide-pencil", onSelect: () => { startEdit(i); } },
          { label: "Delete", icon: "i-lucide-trash-2", destructive: true, onSelect: () => void remove(i) },
        ]
      : []),
  ];
}

const columns: FwListColumn<Item>[] = [
  { accessorKey: "section", header: "Section", cell: ({ row }) => h("span", { class: "font-mono text-[13px]" }, row.original.section) },
  { accessorKey: "property", header: "Property", cell: ({ row }) => h("span", { class: "font-mono text-[13px] text-highlighted" }, row.original.property) },
  {
    accessorKey: "clientId",
    header: "Client",
    phone: "hide",
    cell: ({ row }) => {
      const id = row.original.clientId;
      if (id === undefined) {
        return h("span", { class: "text-muted" }, "Global");
      }
      const label = clientLabel(id);
      return label === id
        ? h("span", { class: "font-mono text-[13px]" }, id)
        : h("span", { class: "font-mono text-[13px]", title: id }, [label, h("span", { class: "text-muted" }, ` (${id})`)]);
    },
  },
  {
    accessorKey: "value",
    header: "Value",
    cell: ({ row }) =>
      row.original.valueType === "SECRET"
        ? h("span", { class: "inline-flex items-center gap-1.5 text-muted", "data-testid": "secret-value" }, [
            h("span", { class: "i-lucide-lock size-3.5", "aria-hidden": "true" }),
            h("span", { "aria-label": "Secret, hidden" }, "••••••••"),
          ])
        : h("span", { class: "font-mono text-[13px] break-all" }, row.original.value),
  },
  { accessorKey: "updatedAt", header: "Updated", phone: "hide", cell: ({ row }) => h(FwLocalTime, { at: row.original.updatedAt }) },
  {
    id: "actions",
    header: () => h("span", { class: "sr-only" }, "Actions"),
    cell: ({ row }) => {
      const all = actionsOf(row.original);
      return all.length === 0 ? "" : h(FwRowActions, { rowLabel: nameOf(row.original), actions: all.slice(0, 2), menu: all.slice(2) });
    },
  },
];

const sectionItems = computed(() => [
  { label: "Any section", value: "any" },
  ...[...new Set(items.value.map((i) => i.section))].sort().map((s) => ({ label: s, value: s })),
]);
const sectionChoice = computed({
  get: () => (section.value === "" ? "any" : section.value),
  set: (next: string) => {
    section.value = next === "any" ? "" : next;
  },
});
const scopeItems = [
  { label: "Global and client", value: "any" },
  { label: "Global", value: "global" },
  { label: "Client", value: "client" },
];
const scopeChoice = computed({
  get: () => scope.value ?? "any",
  set: (next: string) => {
    scope.value = next === "global" || next === "client" ? next : null;
  },
});

// Add and change.
const editorOpen = shallowRef(false);
const editing = shallowRef<Item | undefined>(undefined);

function startAdd(): void {
  editing.value = undefined;
  editorOpen.value = true;
}

function startEdit(item: Item): void {
  editing.value = item;
  editorOpen.value = true;
}

async function save(data: FormData): Promise<ServiceError | undefined> {
  const description = data.description.trim();
  const { data: made, error, response } = await requireCall(admin, "setAdminPlatformConfig")({
    path: { section: data.section, property: data.property },
    ...clientQuery(data.clientId),
    body: { value: data.value, valueType: data.valueType, ...(description === "" ? {} : { description }) },
  });
  if (made === undefined) {
    return explained(toServiceError(error, response));
  }
  flash.success(`${nameOf(made)} saved.`);
  await load();
  return undefined;
}

async function remove(item: Item): Promise<void> {
  const confirmed = await confirm({
    title: `Delete ${nameOf(item)}?`,
    description: "The value is removed from the platform. An application that reads it will no longer find it.",
    confirmLabel: "Delete",
    color: "error",
  });
  if (!confirmed) {
    return;
  }
  const { error, response } = await requireCall(admin, "deleteAdminPlatformConfig")({
    path: { section: item.section, property: item.property },
    ...clientQuery(item.clientId),
  });
  if (error !== undefined) {
    flash.error(explained(toServiceError(error, response)).message);
    return;
  }
  flash.success(`${nameOf(item)} deleted.`);
  await load();
}

// Reveal: the plaintext is held here only while its dialog is open.
const revealed = shallowRef<{ value: string; label: string } | undefined>(undefined);

async function reveal(item: Item): Promise<void> {
  const confirmed = await confirm({
    title: `Reveal ${nameOf(item)}?`,
    description: "The secret is shown to you in plain text. This is recorded against your account.",
    confirmLabel: "Reveal",
  });
  if (!confirmed) {
    return;
  }
  const { data, error, response } = await requireCall(admin, "revealAdminPlatformConfig")({
    path: { section: item.section, property: item.property },
    ...clientQuery(item.clientId),
  });
  if (data === undefined) {
    flash.error(explained(toServiceError(error, response)).message);
    return;
  }
  revealed.value = { value: data.value, label: nameOf(item) };
}
</script>

<template>
  <div class="space-y-4">
    <UAlert v-if="failure !== undefined" color="error" variant="subtle" title="Could not load" :description="failure" data-testid="platform-config-error" />
    <UAlert
      v-if="!allClients"
      color="info"
      variant="subtle"
      icon="i-lucide-info"
      :title="ownClient === undefined ? 'Global values only' : 'Your client only'"
      :description="confinement"
      data-testid="platform-config-confinement"
    />
    <FwListPage
      v-model:search="search"
      title="Platform config"
      subtitle="Values the platform holds for this application. Secrets are never shown unless you reveal one."
      :data="shown"
      :columns="columns"
      :loading="loading"
      :active-filter-count="(section === '' ? 0 : 1) + (scope === null ? 0 : 1)"
      :row-key="(row: Item) => `${row.section}/${row.property}/${row.clientId ?? ''}`"
      empty-message="No properties match."
      search-placeholder="Search section or property…"
      search-loaded-rows
      @refresh="load"
      @clear="list.clearFilters"
    >
      <template #actions>
        <UButton v-if="canManage" label="Add property" icon="i-lucide-plus" data-testid="add-property" @click="startAdd" />
      </template>
      <template #filters>
        <UFormField label="Section">
          <USelect v-model="sectionChoice" :items="sectionItems" class="w-full" data-testid="filter-section" />
        </UFormField>
        <UFormField label="Scope">
          <USelect v-model="scopeChoice" :items="scopeItems" class="w-full" data-testid="filter-scope" />
        </UFormField>
      </template>
    </FwListPage>
    <PlatformConfigDialog v-model:open="editorOpen" :item="editing" :submit="save" :all-clients="allClients" :own-client="ownClient" />
    <PlatformConfigRevealDialog :value="revealed?.value" :label="revealed?.label ?? ''" @close="revealed = undefined" />
  </div>
</template>
