<script setup lang="ts">
import { FwEmptyState, FwPageHeader, FwSpinner, FwStatusBadge, FwTag, useFlash, type TagSeverity } from "@flowcatalyst/ui";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UBadge from "@nuxt/ui/components/Badge.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UProgress from "@nuxt/ui/components/Progress.vue";
import USkeleton from "@nuxt/ui/components/Skeleton.vue";
import { shallowRef } from "vue";
import { getOrder } from "../api/orders-demo/sdk.gen.ts";
import GallerySection from "../components/GallerySection.vue";

const flash = useFlash();
const severities: TagSeverity[] = ["success", "info", "warn", "danger", "secondary", "primary"];
const badgeColors = ["primary", "success", "info", "warning", "error", "neutral"] as const;
const statusMap: Readonly<Record<string, TagSeverity>> = { OPEN: "info", PLACED: "success", CANCELLED: "secondary", FAILED: "danger" };
const filtered = shallowRef(true);

/** Calls the fake server with ids that answer 403, 500 or a network failure (src/fake-server.ts). */
async function fail(id: "fail-403" | "fail-500" | "fail-network"): Promise<void> {
  await getOrder({ path: { orderId: id } });
}
</script>

<template>
  <div class="space-y-6">
    <FwPageHeader title="Feedback" subtitle="Alerts, toasts, badges and tags, progress, skeletons, spinners and empty states" />

    <GallerySection spec="alert flash-banner" title="Alert and flash banner" description="An inline banner rendered with the page (after a redirect, a form-level error).">
      <UAlert title="Order saved" description="Order 1042 was saved." color="success" variant="outline" icon="i-lucide-circle-check" />
      <UAlert title="The import is running" description="New orders appear when it finishes." color="info" variant="outline" icon="i-lucide-info" />
      <UAlert title="Validation failed" description="Two fields need attention." color="error" variant="outline" icon="i-lucide-circle-x" />
      <UAlert title="Neutral note" description="Dismissible." color="neutral" variant="subtle" close />
    </GallerySection>

    <GallerySection spec="flash-stack" title="Flash messages (useFlash) and request failures" description="Success and info leave after 3 s and 5 s; warnings and errors stay until dismissed. A refused, failed or unreachable call shows the fallback message once.">
      <div class="flex flex-wrap gap-2">
        <UButton label="Success" color="neutral" variant="outline" data-testid="flash-success" @click="flash.success('Order 1042 was saved.')" />
        <UButton label="Info" color="neutral" variant="outline" data-testid="flash-info" @click="flash.info('The export started.', { detail: 'You will get an email.' })" />
        <UButton label="Warning" color="neutral" variant="outline" data-testid="flash-warn" @click="flash.warn('Two lines could not be priced.')" />
        <UButton label="Error" color="neutral" variant="outline" data-testid="flash-error" @click="flash.error('Could not save order 1042.', { detail: 'The order was changed by someone else.' })" />
      </div>
      <div class="flex flex-wrap gap-2">
        <UButton label="A refused call (403)" color="neutral" variant="ghost" data-testid="fail-403" @click="fail('fail-403')" />
        <UButton label="A failed call (500)" color="neutral" variant="ghost" data-testid="fail-500" @click="fail('fail-500')" />
        <UButton label="No network" color="neutral" variant="ghost" data-testid="fail-network" @click="fail('fail-network')" />
      </div>
    </GallerySection>

    <GallerySection spec="tag" title="Tag (FwTag) and status badge (FwStatusBadge)" description="The kit's six severities; a status mapped once per application.">
      <div class="flex flex-wrap gap-2">
        <FwTag v-for="severity in severities" :key="severity" :label="severity" :severity="severity" />
      </div>
      <div class="flex flex-wrap gap-2">
        <FwStatusBadge v-for="status in ['OPEN', 'PLACED', 'CANCELLED', 'FAILED', 'IN_REVIEW']" :key="status" :status="status" :severities="statusMap" />
      </div>
    </GallerySection>

    <GallerySection spec="badge" title="Badge" description="UBadge colours in the solid and outline variants.">
      <div class="flex flex-wrap gap-2">
        <UBadge v-for="color in badgeColors" :key="color" :label="color" :color="color" />
      </div>
      <div class="flex flex-wrap gap-2">
        <UBadge v-for="color in badgeColors" :key="color" :label="color" :color="color" variant="outline" />
      </div>
    </GallerySection>

    <GallerySection spec="progress skeleton" title="Progress and skeleton">
      <UProgress :model-value="40" class="w-60" aria-label="Import progress" />
      <div class="space-y-2">
        <USkeleton class="h-4 w-60" />
        <USkeleton class="h-4 w-40" />
      </div>
    </GallerySection>

    <GallerySection spec="spinner" title="Spinner (FwSpinner)" description="Alone it is an image named 'Loading'; inside a labelled button it is decorative.">
      <div class="flex items-center gap-4">
        <FwSpinner />
        <FwSpinner size="size-4" label="Loading orders" />
        <UButton disabled color="neutral" variant="outline">
          <FwSpinner decorative size="size-4" /> Saving
        </UButton>
      </div>
    </GallerySection>

    <GallerySection spec="empty-state" title="Empty state (FwEmptyState)" description="With a filter active it offers Clear filters.">
      <div class="grid gap-4 sm:grid-cols-2">
        <div class="rounded-[2px] border border-default"><FwEmptyState message="No dispatch jobs yet." /></div>
        <div class="rounded-[2px] border border-default">
          <FwEmptyState v-if="filtered" message="No orders match these filters." :filtered="true" icon="i-lucide-search-x" data-testid="empty-filtered" @clear="filtered = false" />
          <FwEmptyState v-else message="Filters cleared." icon="i-lucide-check" />
        </div>
      </div>
    </GallerySection>
  </div>
</template>
