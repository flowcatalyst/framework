<script setup lang="ts">
// The framework's about page (FwAdminAbout) on the generated getAdminAbout.
import { ApiRequestError, toServiceError } from "@flowcatalyst/api-runtime";
import { FwAdminAbout, type AdminAboutView } from "@flowcatalyst/ui";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import { onMounted, shallowRef } from "vue";
import { getAdminAbout } from "../../api/orders/sdk.gen.ts";

const about = shallowRef<AdminAboutView>();
const failure = shallowRef<string>();

async function load(): Promise<void> {
  failure.value = undefined;
  try {
    const { data, error, response } = await getAdminAbout();
    if (data === undefined) {
      throw new ApiRequestError(toServiceError(error, response));
    }
    about.value = data;
  } catch (cause) {
    failure.value = cause instanceof ApiRequestError ? cause.message : "The about page could not be loaded.";
  }
}
onMounted(load);
</script>

<template>
  <div>
    <UAlert v-if="failure !== undefined" color="error" variant="subtle" title="Could not load" :description="failure" data-testid="admin-about-failed">
      <template #actions><UButton label="Try again" color="error" variant="outline" @click="load" /></template>
    </UAlert>
    <FwAdminAbout v-else-if="about !== undefined" :about="about" />
  </div>
</template>
