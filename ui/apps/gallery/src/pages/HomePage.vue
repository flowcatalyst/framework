<script setup lang="ts">
import ULink from "@nuxt/ui/components/Link.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import { behaviours } from "../behaviours.ts";

const fromNuxtUi = behaviours.filter((row) => /^U[A-Z]/.test(row.by)).length;
</script>

<template>
  <div>
    <UPageHeader title="FlowCatalyst UI gallery" description="The platform behaviours @flowcatalyst/ui provides, on Nuxt UI, under the strict CSP" />
    <p class="mb-4 text-sm text-muted">
      {{ behaviours.length }} behaviours: {{ fromNuxtUi }} by a Nuxt UI component used directly, the others by a FlowCatalyst part
      built on Nuxt UI (docs/ui/nuxt-native.md). Each one is exercised by a Playwright test.
    </p>
    <table class="w-full text-sm" data-testid="behaviours">
      <caption class="sr-only">Platform behaviours and the part that provides each</caption>
      <thead>
        <tr class="border-b border-default text-left text-muted">
          <th class="py-2 font-medium">Behaviour</th>
          <th class="py-2 font-medium">Provided by</th>
          <th class="py-2 font-medium">Page</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in behaviours" :key="row.id" class="border-b border-default align-top">
          <td class="py-1.5 pr-3">{{ row.behaviour }}</td>
          <td class="py-1.5 pr-3 font-mono text-[13px]">{{ row.by }}</td>
          <td class="py-1.5">
            <ULink v-if="row.page !== '*'" :to="row.page" class="text-highlighted underline">{{ row.page }}</ULink>
            <span v-else class="text-muted">every page</span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
