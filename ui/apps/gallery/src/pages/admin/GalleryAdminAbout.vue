<script setup lang="ts">
// FwAdminAbout on the fake server's about answer.
import { FwAdminAbout } from "@flowcatalyst/ui";
import { fakeAdminAbout } from "../../fake-server.ts";

const about = fakeAdminAbout();
</script>

<template>
  <div class="space-y-6">
    <FwAdminAbout :about="about" />
    <section class="border-t border-default pt-4 text-sm" data-testid="view-as">
      <h2 class="mb-1 text-base font-semibold text-highlighted">View this area as</h2>
      <ul class="list-disc space-y-1 pl-5">
        <li><a class="text-primary underline" href="/admin/about?as=admin" data-testid="as-admin">an administrator (every admin permission)</a></li>
        <li><a class="text-primary underline" href="/admin/about?as=users" data-testid="as-users">a users administrator (console and users only)</a></li>
        <li><a class="text-primary underline" href="/admin/about?as=none" data-testid="as-none">someone with no admin permission</a></li>
        <li><a class="text-primary underline" href="/admin/about?as=down" data-testid="as-down">an administrator whose navigation fails to load once</a></li>
      </ul>
    </section>
  </div>
</template>
