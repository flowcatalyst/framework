<script setup lang="ts">
import { FwPageHeader } from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import { pages } from "../router.ts";

const sections = pages.filter((page) => page.path !== "/");
</script>

<template>
  <div>
    <FwPageHeader title="FlowCatalyst UI gallery" subtitle="Nuxt UI with the FlowCatalyst theme, under the strict CSP" />
    <p class="mb-4 text-sm text-muted">
      Every component and partial of @flowcatalyst/ui gets a page here with every state its ui-kit spec names (U1 and U2).
      At U0 the pages exercise Nuxt UI itself, to show that it runs under the strict Content-Security-Policy.
    </p>
    <nav aria-label="Gallery sections" class="flex flex-wrap gap-2" data-testid="sections">
      <UButton v-for="page in sections" :key="page.path" :to="page.path" :label="page.title" color="neutral" variant="outline" />
    </nav>
  </div>
</template>
