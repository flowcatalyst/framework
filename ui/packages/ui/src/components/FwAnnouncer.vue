<script setup lang="ts">
// The application's two live regions (useAnnouncer): render once, near the root, from page load.
// FwAppShell includes it; an application without the shell puts it in its root component.
import { announcements } from "../composables/useAnnouncer.ts";
</script>

<template>
  <div class="sr-only">
    <div role="status" aria-live="polite" aria-atomic="true" data-testid="fw-announcer-polite">{{ announcements.polite.value }}</div>
    <div role="alert" aria-live="assertive" aria-atomic="true" data-testid="fw-announcer-assertive">{{ announcements.assertive.value }}</div>
  </div>
</template>
