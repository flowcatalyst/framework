<script setup lang="ts">
// The kit's pages outside the shell (U4). Each demo is its own public route, rendered without the
// shell, because that is what they are for; this page links to them.
import { SIGN_IN_FAILURE_MESSAGES } from "@flowcatalyst/ui";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import { RouterLink } from "vue-router";
import GallerySection from "../components/GallerySection.vue";

const codes = Object.keys(SIGN_IN_FAILURE_MESSAGES);
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="Standalone pages" description="Signed out, sign-in failed and unavailable, outside the shell" />
    <GallerySection
      :behaviours="['standalone-layout']"
      title="FwStandalonePage"
      description="The layout of every page that needs no session: the application name, the page's one h1, a body and the ways out in a centred column, with the live regions. The application routes such pages with meta.public and renders them without FwAppShell."
    />
    <GallerySection
      :behaviours="['signed-out-page']"
      title="FwSignedOutPage"
      description="Where logout ends. Sign in again goes through the session (here the gallery's stand-in) to the given path."
    >
      <RouterLink class="text-primary underline" to="/standalone/signed-out" data-testid="demo-signed-out">Open the signed-out page</RouterLink>
    </GallerySection>
    <GallerySection
      :behaviours="['sign-in-failed-page']"
      title="FwSignInFailedPage"
      description="Where a failed sign-in lands: a sentence per code of the sessions contract (section 4.2), the code as text, Try again. Any other code gets the general sentence."
    >
      <ul class="list-disc space-y-1 pl-5 text-sm">
        <li v-for="code in codes" :key="code">
          <RouterLink class="text-primary underline" :to="{ path: '/standalone/sign-in-failed', query: { error: code } }" :data-testid="`demo-failed-${code}`">{{ code }}</RouterLink>
        </li>
        <li><RouterLink class="text-primary underline" :to="{ path: '/standalone/sign-in-failed', query: { error: 'SOMETHING_ELSE' } }" data-testid="demo-failed-other">an unknown code</RouterLink></li>
      </ul>
    </GallerySection>
    <GallerySection
      :behaviours="['unavailable-page']"
      title="FwUnavailablePage"
      description="The session could not be read at all (installSessionGuard's onUnavailable). Try again loads the page again in full."
    >
      <RouterLink class="text-primary underline" to="/standalone/unavailable" data-testid="demo-unavailable">Open the unavailable page</RouterLink>
    </GallerySection>
  </div>
</template>
