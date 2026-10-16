<script setup lang="ts">
// The skeleton's about page (docs/contracts/admin.md section 4.2), presentational: the application's
// page loads GET /api/admin/about with its generated client and passes the answer in. Failure messages
// are not on the wire; the logs have them.
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import FwDetailField from "../components/FwDetailField.vue";
import FwDetailList from "../components/FwDetailList.vue";
import FwLocalTime from "../components/FwLocalTime.vue";
import FwStatusBadge from "../components/FwStatusBadge.vue";
import type { AdminAboutView } from "../admin/types.ts";
import type { StatusColor } from "../types.ts";

defineProps<{ about: AdminAboutView }>();

const STATUS_COLORS: Readonly<Record<string, StatusColor>> = { UP: "success", DOWN: "error", TIMED_OUT: "warning" };
const STATUS_LABELS: Readonly<Record<string, string>> = { UP: "Up", DOWN: "Down", TIMED_OUT: "Timed out" };
</script>

<template>
  <div class="space-y-6" data-testid="fw-admin-about">
    <UPageHeader title="About" description="What this application is and whether it is healthy." />
    <FwDetailList>
      <FwDetailField label="Application" :value="about.application" />
      <FwDetailField label="Environment" :value="about.environment" />
      <FwDetailField label="Framework version" :value="about.framework" />
      <FwDetailField label="Started"><FwLocalTime :at="about.startedAt" /></FwDetailField>
      <FwDetailField label="Ready">
        <FwStatusBadge :status="about.ready ? 'UP' : 'DOWN'" :colors="STATUS_COLORS" :label="about.ready ? 'Ready' : 'Not ready'" />
      </FwDetailField>
    </FwDetailList>
    <section aria-labelledby="fw-admin-checks">
      <h2 id="fw-admin-checks" class="mb-2 text-base font-semibold text-highlighted">Health checks</h2>
      <p v-if="about.checks.length === 0" class="text-sm text-muted">No health checks are registered.</p>
      <ul v-else class="divide-y divide-default rounded-md border border-default" data-testid="fw-admin-checks">
        <li v-for="check in about.checks" :key="check.name" class="flex items-center justify-between gap-3 px-3 py-2 text-sm">
          <span class="min-w-0 truncate font-mono">{{ check.name }}<span v-if="!check.critical" class="ml-2 text-muted">(informational)</span></span>
          <FwStatusBadge :status="check.status" :colors="STATUS_COLORS" :label="STATUS_LABELS[check.status] ?? check.status" />
        </li>
      </ul>
    </section>
  </div>
</template>
