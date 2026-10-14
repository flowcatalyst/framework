<script setup lang="ts">
import { FwPageHeader } from "@flowcatalyst/ui";
import GallerySection from "../components/GallerySection.vue";
</script>

<template>
  <div class="space-y-6">
    <FwPageHeader title="Application shell" subtitle="The gallery itself runs in FwAppShell" />
    <GallerySection spec="app-shell sidebar" title="FwAppShell" description="The page around this text: the navy sidebar (UDashboardSidebar; collapsible on desktop, a drawer on phones), navigation filtered by the session's permissions (the 'Administration' entry needs a permission the session lacks, so it is not shown), the account menu with Sign out, the colour-mode button, the navbar with the page title as text (the page's one h1 is FwPageHeader's), and request-failure toasts.">
      <ul class="list-disc space-y-1 pl-5 text-sm">
        <li>Collapse the sidebar with the button in the navbar; on a phone it opens as a drawer.</li>
        <li>The account menu at the bottom of the sidebar signs out through the runtime (POST /auth/logout).</li>
        <li>Navigation links mark the current page with aria-current.</li>
      </ul>
    </GallerySection>
  </div>
</template>
