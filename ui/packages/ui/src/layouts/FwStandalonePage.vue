<script setup lang="ts">
// A page OUTSIDE the shell (docs/ui/u3.md G10; the signed-out, sign-in-failed, unavailable and similar
// pages): no session is needed, so no sidebar or navbar. A centred column with the application's name,
// the page's one <h1> (UPageHeader), a body and actions, in its own <main>, with the live regions
// (FwAnnouncer). The application routes such pages with `meta.public` and renders them without
// FwAppShell:
//
//   <UApp><RouterView v-if="route.meta.public === true" /><FwAppShell v-else ...><RouterView /></FwAppShell></UApp>
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import FwAnnouncer from "../components/FwAnnouncer.vue";

const {
  title,
  description,
  appName,
} = defineProps<{
  title: string;
  description?: string;
  /** Shown above the title, so a stranded user knows whose page this is. */
  appName?: string;
}>();

const slots = defineSlots<{
  /** More content under the title (a code, a hint). */
  default?: () => unknown;
  /** The ways out: buttons and links. */
  actions?: () => unknown;
}>();
</script>

<template>
  <main class="mx-auto flex min-h-dvh w-full max-w-xl flex-col justify-center gap-4 p-6" data-testid="fw-standalone-page">
    <p v-if="appName !== undefined" class="text-sm font-semibold text-muted" data-testid="fw-standalone-app">{{ appName }}</p>
    <UPageHeader :title="title" v-bind="description === undefined ? {} : { description }" />
    <div v-if="slots.default !== undefined" class="text-sm" data-testid="fw-standalone-body"><slot /></div>
    <div v-if="slots.actions !== undefined" class="flex flex-wrap items-center gap-2" data-testid="fw-standalone-actions"><slot name="actions" /></div>
    <FwAnnouncer />
  </main>
</template>
