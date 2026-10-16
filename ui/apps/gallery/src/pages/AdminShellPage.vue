<script setup lang="ts">
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import GallerySection from "../components/GallerySection.vue";
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="Admin shell" description="FwAdminShell: the application's administration area (M4d)" />
    <GallerySection
      :behaviours="['admin-nav-permissions', 'admin-unlisted-section']"
      title="Navigation from the server and the session"
      description="The area's navigation is the sections the server lists for the caller (GET /api/admin/navigation) that the application has a page for and the session's permissions open. The fake server also lists 'events', which this application has no page for: it is not shown."
    >
      <ul class="list-disc space-y-1 pl-5 text-sm">
        <li><a class="text-primary underline" href="/admin/about?as=admin" data-testid="open-admin">Open the area as an administrator</a></li>
        <li><a class="text-primary underline" href="/admin/about?as=users" data-testid="open-users">Open it as a users administrator</a></li>
      </ul>
    </GallerySection>
    <GallerySection
      :behaviours="['admin-route-guard']"
      title="Route guard"
      description="Every route of the area carries its permission (adminRoutes refuses a page without one) and the session guard sends a session without it to the not-permitted page. The server checks every operation regardless."
    >
      <a class="text-primary underline text-sm" href="/admin/roles?as=users" data-testid="open-denied">Open Roles as a users administrator</a>
    </GallerySection>
    <GallerySection
      :behaviours="['admin-breadcrumbs']"
      title="Breadcrumbs"
      description="Admin, then the section, then a record; the current page is text marked aria-current, the rest are links."
    />
    <GallerySection
      :behaviours="['admin-nav-failed']"
      title="A navigation that cannot be loaded"
      description="The page still opens, an inline error says the menu could not be loaded, and Try again loads it."
    >
      <a class="text-primary underline text-sm" href="/admin/about?as=down" data-testid="open-down">Open the area with a failing navigation</a>
    </GallerySection>
  </div>
</template>
