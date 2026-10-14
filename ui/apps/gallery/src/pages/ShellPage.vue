<script setup lang="ts">
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import GallerySection from "../components/GallerySection.vue";
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="Application shell" description="The gallery itself runs in FwAppShell" />
    <GallerySection
      :behaviours="['shell-nav-permissions', 'shell-current-page', 'shell-sign-out', 'shell-sidebar-collapse', 'shell-phone-sidebar', 'colour-mode']"
      title="FwAppShell"
      description="The page around this text: Nuxt UI's dashboard layout with the navy sidebar (collapsible on desktop, a slideover on phones), navigation filtered by the session's permissions (the 'Administration' entry needs a permission the session lacks, so it is not shown), the account menu with Sign out, the colour-mode button, the navbar with the page title as text (the page's one h1 is its UPageHeader), and request-failure toasts."
    >
      <ul class="list-disc space-y-1 pl-5 text-sm">
        <li>Collapse the sidebar to an icon rail with the toggle at the top of the sidebar (names show as tooltips); the choice is remembered in this browser. On a phone the sidebar opens from the navbar as a slideover instead.</li>
        <li>The account menu at the bottom of the sidebar signs out through the runtime (POST /auth/logout).</li>
        <li>Navigation links mark the current page with aria-current.</li>
        <li>The colour-mode button cycles light, dark and system; the choice is stored and applied before the first paint.</li>
      </ul>
    </GallerySection>
  </div>
</template>
