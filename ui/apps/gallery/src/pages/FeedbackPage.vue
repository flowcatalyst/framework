<script setup lang="ts">
import { FwStatusBadge, useFlash, type StatusColor } from "@flowcatalyst/ui";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UBadge from "@nuxt/ui/components/Badge.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UEmpty from "@nuxt/ui/components/Empty.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import UProgress from "@nuxt/ui/components/Progress.vue";
import USkeleton from "@nuxt/ui/components/Skeleton.vue";
import { shallowRef } from "vue";
import { getOrder } from "../api/orders-demo/sdk.gen.ts";
import GallerySection from "../components/GallerySection.vue";

const flash = useFlash();
const badgeColors = ["primary", "success", "info", "warning", "error", "neutral"] as const;
const statusColors: Readonly<Record<string, StatusColor>> = { OPEN: "info", PLACED: "success", ON_HOLD: "warning", CANCELLED: "neutral", FAILED: "error" };
const imported = shallowRef(false);

/** Calls the fake server with ids that answer 403, 500 or a network failure (src/fake-server.ts). */
async function fail(id: "fail-403" | "fail-500" | "fail-network"): Promise<void> {
  await getOrder({ path: { orderId: id } });
}
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="Feedback" description="Alerts, toasts, badges, progress, skeletons and empty states" />

    <GallerySection title="Alert (UAlert)" description="An inline banner rendered with the page (after a redirect, a form-level error). Descriptions at full opacity (fcComponentTheme).">
      <UAlert title="Order saved" description="Order 1042 was saved." color="success" variant="outline" icon="i-lucide-circle-check" />
      <UAlert title="The import is running" description="New orders appear when it finishes." color="info" variant="outline" icon="i-lucide-info" />
      <UAlert title="Two lines could not be priced" description="Check the price list." color="warning" variant="outline" icon="i-lucide-triangle-alert" />
      <UAlert title="Validation failed" description="Two fields need attention." color="error" variant="outline" icon="i-lucide-circle-x" />
      <UAlert title="Neutral note" description="Dismissible." color="neutral" variant="subtle" close />
    </GallerySection>

    <GallerySection
      :behaviours="['flash-announced', 'flash-timing', 'request-failures']"
      title="Flash messages (useFlash) and request failures (useRequestFailureToasts)"
      description="UToast, announced through FwAnnouncer. Success and info leave after 3 s and 5 s; warnings and errors stay until dismissed. A refused, failed or unreachable call shows the fallback message once."
    >
      <div class="flex flex-wrap gap-2">
        <UButton label="Success" color="neutral" variant="outline" data-testid="flash-success" @click="flash.success('Order 1042 was saved.')" />
        <UButton label="Info" color="neutral" variant="outline" data-testid="flash-info" @click="flash.info('The export started.', { detail: 'You will get an email.' })" />
        <UButton label="Warning" color="neutral" variant="outline" data-testid="flash-warning" @click="flash.warning('Two lines could not be priced.')" />
        <UButton label="Error" color="neutral" variant="outline" data-testid="flash-error" @click="flash.error('Could not save order 1042.', { detail: 'The order was changed by someone else.' })" />
      </div>
      <div class="flex flex-wrap gap-2">
        <UButton label="A refused call (403)" color="neutral" variant="ghost" data-testid="fail-403" @click="fail('fail-403')" />
        <UButton label="A failed call (500)" color="neutral" variant="ghost" data-testid="fail-500" @click="fail('fail-500')" />
        <UButton label="No network" color="neutral" variant="ghost" data-testid="fail-network" @click="fail('fail-network')" />
      </div>
    </GallerySection>

    <GallerySection :behaviours="['status-badge']" title="Status badge (FwStatusBadge)" description="A domain status on UBadge: the application maps its statuses to colours once; the label is sentence case; an unmapped status (IN_REVIEW) is neutral.">
      <div class="flex flex-wrap gap-2" data-testid="status-badges">
        <FwStatusBadge v-for="status in ['OPEN', 'PLACED', 'ON_HOLD', 'CANCELLED', 'FAILED', 'IN_REVIEW']" :key="status" :status="status" :colors="statusColors" />
      </div>
    </GallerySection>

    <GallerySection title="Badge (UBadge)" description="Every colour, solid and outline: the variants FlowCatalyst uses (docs/ui/u1.md, contrast).">
      <div class="flex flex-wrap gap-2">
        <UBadge v-for="color in badgeColors" :key="color" :label="color" :color="color" />
      </div>
      <div class="flex flex-wrap gap-2">
        <UBadge v-for="color in badgeColors" :key="color" :label="color" :color="color" variant="outline" />
      </div>
    </GallerySection>

    <GallerySection :behaviours="['loading-named']" title="Progress, loading and skeleton (UProgress, UButton, USkeleton)" description="A named progress bar, an indeterminate one, a busy button; skeletons for content still loading.">
      <!-- UProgress puts attributes on its wrapper: the progress bar is named by getValueLabel. -->
      <UProgress :model-value="40" class="w-60" :get-value-label="(value) => `Import progress, ${String(value ?? 0)}%`" />
      <UProgress class="w-60" :get-value-label="() => 'Loading orders'" />
      <UButton label="Saving" loading color="neutral" variant="outline" data-testid="busy-button" />
      <div class="space-y-2">
        <USkeleton class="h-4 w-60" />
        <USkeleton class="h-4 w-40" />
      </div>
    </GallerySection>

    <GallerySection :behaviours="['empty-state']" title="Empty state (UEmpty)" description="What is missing and the next step. A list's empty state (FwListPage) adds Clear filters while filtered.">
      <div class="grid gap-4 sm:grid-cols-2">
        <div class="rounded-[2px] border border-default">
          <UEmpty
            v-if="!imported"
            icon="i-lucide-inbox"
            title="No dispatch jobs yet"
            description="Jobs appear when the first event is dispatched."
            :actions="[{ label: 'Import jobs', color: 'neutral', variant: 'outline', onClick: () => { imported = true; } }]"
            variant="naked"
            data-testid="empty-jobs"
          />
          <UEmpty v-else icon="i-lucide-check" title="Import started" variant="naked" />
        </div>
        <div class="rounded-[2px] border border-default">
          <UEmpty icon="i-lucide-search-x" description="No orders match these filters." variant="naked" />
        </div>
      </div>
    </GallerySection>
  </div>
</template>
