<script setup lang="ts">
import { FwPageHeader, FwTag, type TagSeverity } from "@flowcatalyst/ui";
import ULink from "@nuxt/ui/components/Link.vue";
import { catalog, type SpecStatus } from "../catalog.ts";

const SEVERITY: Readonly<Record<SpecStatus, TagSeverity>> = { covered: "secondary", composed: "info", built: "primary", "not ported": "warn" };
const counts = (["covered", "composed", "built", "not ported"] as const).map((status) => ({ status, count: catalog.filter((e) => e.status === status).length }));
</script>

<template>
  <div>
    <FwPageHeader title="FlowCatalyst UI gallery" subtitle="Every ui-kit spec as @flowcatalyst/ui implements it, under the strict CSP" />
    <p class="mb-4 text-sm text-muted">
      <template v-for="(entry, index) in counts" :key="entry.status">{{ index > 0 ? ", " : "" }}{{ entry.count }} {{ entry.status }}</template>
      (docs/go/ui.md section 10). Each entry links to the page that shows it.
    </p>
    <table class="w-full text-sm" data-testid="catalog">
      <caption class="sr-only">ui-kit specs and their implementation</caption>
      <thead>
        <tr class="border-b border-default text-left text-muted">
          <th class="py-2 font-medium">Spec</th>
          <th class="py-2 font-medium">Status</th>
          <th class="py-2 font-medium">Implemented by</th>
          <th class="py-2 font-medium">Page</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="entry in catalog" :key="entry.spec" class="border-b border-default">
          <td class="py-1.5 font-mono text-[13px]">{{ entry.spec }}</td>
          <td class="py-1.5"><FwTag :label="entry.status" :severity="SEVERITY[entry.status]" /></td>
          <td class="py-1.5">{{ entry.by }}</td>
          <td class="py-1.5">
            <ULink v-if="entry.page !== ''" :to="entry.page" class="text-highlighted underline">{{ entry.page }}</ULink>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
