<script setup lang="ts">
// The font comparison's screens (docs/ui/fonts.md): glyph samples, a dense table of IDs, timestamps
// and numerals, a form, a code block. The shell around this page (navigation, header, footer) is
// part of the comparison too. `?font=<id>` (src/fonts.ts) swaps the font for the whole page; the
// switcher below sets it. Only the dev server and `--mode fonts` have the fonts.
import { FwCodeBlock, FwStatusBadge, type StatusColor } from "@flowcatalyst/ui";
import type { TableColumn } from "@nuxt/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import USelect from "@nuxt/ui/components/Select.vue";
import UTable from "@nuxt/ui/components/Table.vue";
import UTextarea from "@nuxt/ui/components/Textarea.vue";
import { computed, h, shallowRef } from "vue";
import { useRoute } from "vue-router";
import GallerySection from "../components/GallerySection.vue";
import { comparisonFontFor, comparisonFonts, fontComparisonAvailable } from "../fonts.ts";

const route = useRoute();
const current = computed(() => comparisonFontFor(route.query["font"])?.id ?? "ibm-plex-sans");

interface Delivery {
  readonly id: string;
  readonly code: string;
  readonly at: string;
  readonly status: "DELIVERED" | "PENDING" | "FAILED";
  readonly attempts: number;
  readonly latencyMs: number;
  readonly amount: string;
}

const colors: Readonly<Record<Delivery["status"], StatusColor>> = { DELIVERED: "success", PENDING: "info", FAILED: "error" };
const rows: Delivery[] = [
  { id: "0HZXK3J8M4P2Q", code: "orders:order:placed", at: "2026-10-14T08:01:09.412Z", status: "DELIVERED", attempts: 1, latencyMs: 112, amount: "1,204.50" },
  { id: "0HZXK3J8M4P2R", code: "orders:order:placed", at: "2026-10-14T08:01:09.998Z", status: "DELIVERED", attempts: 1, latencyMs: 98, amount: "89.00" },
  { id: "0HZXK3J9A0L1I", code: "billing:invoice:issued", at: "2026-10-14T08:01:11.007Z", status: "PENDING", attempts: 2, latencyMs: 1480, amount: "10,000.00" },
  { id: "0HZXK3J9A0LlO", code: "billing:invoice:voided", at: "2026-10-14T08:02:30.250Z", status: "FAILED", attempts: 5, latencyMs: 30001, amount: "0.00" },
  { id: "0HZXK3JB77Q0O", code: "stock:level:changed", at: "2026-10-14T08:02:31.731Z", status: "DELIVERED", attempts: 1, latencyMs: 41, amount: "1,111.11" },
  { id: "0HZXK3JB77Q01", code: "stock:level:changed", at: "2026-10-14T08:02:32.004Z", status: "DELIVERED", attempts: 1, latencyMs: 57, amount: "7.77" },
  { id: "0HZXK3JC2D9W5", code: "orders:order:cancelled", at: "2026-10-14T08:04:00.000Z", status: "PENDING", attempts: 3, latencyMs: 2210, amount: "-248.80" },
  { id: "0HZXK3JC2D9W6", code: "orders:order:shipped", at: "2026-10-14T08:04:18.519Z", status: "DELIVERED", attempts: 1, latencyMs: 76, amount: "3,450.25" },
];

const columns: TableColumn<Delivery>[] = [
  { accessorKey: "id", header: "Dispatch ID", meta: { class: { td: "tabular-nums" } } },
  { accessorKey: "code", header: "Event code" },
  { accessorKey: "at", header: "Created (UTC)", meta: { class: { td: "tabular-nums" } } },
  { accessorKey: "status", header: "Status", cell: ({ row }) => h(FwStatusBadge, { status: row.original.status, colors }) },
  { accessorKey: "attempts", header: "Attempts", meta: { class: { td: "text-right tabular-nums", th: "text-right" } } },
  { accessorKey: "latencyMs", header: "Latency (ms)", meta: { class: { td: "text-right tabular-nums", th: "text-right" } } },
  { accessorKey: "amount", header: "Amount", meta: { class: { td: "text-right tabular-nums", th: "text-right" } } },
];

const code = `// 0 O o, 1 l I, 5 S, 8 B: a dispatch of order ord_1041
const attempt = await send({ id: "0HZXK3J8M4P2Q", at: "2026-10-14T08:01:09.412Z", ok: 1 !== 0 });
if (!attempt.ok && retries <= 5) { schedule(Math.min(2 ** retries * 1000, 30_000)); }`;

const name = shallowRef("Acme Trading (Pty) Ltd");
const endpoint = shallowRef("https://hooks.example.com/dispatch/1l0O");
const queue = shallowRef("orders-high");
const note = shallowRef("Ünïcödé: Zażółć gęślą jaźń, Ελληνικά, Кириллица, Tiếng Việt.");
const queues = ["orders-high", "orders-default", "billing"];
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="Font comparison" description="The same screens in IBM Plex Sans (the default), Inter and two other candidates; the whole page, shell included, uses the chosen font">
      <template #links>
        <UButton label="Primary action" color="neutral" variant="outline" />
      </template>
    </UPageHeader>

    <GallerySection title="Font" description="Sets ?font=… (kept while you move between pages). Fonts are self-hosted and load only in the dev server and in `pnpm gallery:fonts`.">
      <div v-if="fontComparisonAvailable" class="flex flex-wrap gap-2" role="group" aria-label="Font">
        <UButton
          v-for="font in comparisonFonts"
          :key="font.id"
          :label="font.label"
          :to="{ path: '/fonts', query: { font: font.id } }"
          :color="current === font.id ? 'primary' : 'neutral'"
          :variant="current === font.id ? 'solid' : 'outline'"
          :aria-current="current === font.id ? 'true' : undefined"
          :data-testid="`font-${font.id}`"
        />
      </div>
      <p v-else class="text-sm text-muted" data-testid="fonts-unavailable">
        The comparison fonts are not in this build (the default production build ships IBM Plex Sans only).
      </p>
      <p class="text-sm" data-testid="font-current">Showing: {{ comparisonFonts.find((font) => font.id === current)?.label }}</p>
    </GallerySection>

    <GallerySection title="Glyphs" description="Look-alikes (1 l I | 0 O o), numerals, punctuation, and scripts the platform's users write in.">
      <div class="space-y-1">
        <p class="text-2xl" data-testid="glyph-sample">Il1| 0Oo rn m {}[]() &lt;&gt; ,.;:</p>
        <p class="text-sm">The quick brown fox jumps over the lazy dog. 0123456789</p>
        <p class="text-sm tabular-nums">Tabular: 1111.11 / 8888.88 / 0000.00 · 2026-10-14 08:01:09</p>
        <p class="text-sm">Proportional: 1111.11 / 8888.88 / 0000.00 · 2026-10-14 08:01:09</p>
        <p class="text-sm">Zażółć gęślą jaźń · Ελληνικά · Кириллица · Tiếng Việt · Ærø Çağrı Åland</p>
        <p class="text-xs text-muted">Small text, 12 px: Dispatch 0HZXK3J8M4P2Q failed after 5 attempts (HTTP 503, 30001 ms).</p>
      </div>
    </GallerySection>

    <GallerySection title="Dense table" description="IDs, event codes, timestamps and right-aligned numerals (tabular figures).">
      <div class="overflow-x-auto" tabindex="0" role="region" aria-label="Dispatches table">
        <UTable :data="rows" :columns="columns" caption="Dispatches" :get-row-id="(row: Delivery) => row.id" :ui="{ root: 'overflow-visible', td: 'py-2', th: 'py-2' }" />
      </div>
    </GallerySection>

    <GallerySection title="Form" description="Labels, inputs, a select, help text and an error.">
      <form class="grid max-w-xl gap-4" novalidate aria-label="Subscription" @submit.prevent>
        <UFormField label="Name" name="name" required>
          <UInput v-model="name" class="w-full" />
        </UFormField>
        <UFormField label="Endpoint" name="endpoint" description="Called with the signed event." error="Use https and a host without a port.">
          <UInput v-model="endpoint" class="w-full" />
        </UFormField>
        <UFormField label="Queue" name="queue" help="Messages in one group keep their order.">
          <USelect v-model="queue" :items="queues" class="w-full" />
        </UFormField>
        <UFormField label="Note" name="note">
          <UTextarea v-model="note" class="w-full" />
        </UFormField>
        <div class="flex gap-2">
          <UButton type="submit" label="Save subscription" />
          <UButton label="Cancel" color="neutral" variant="outline" />
        </div>
      </form>
    </GallerySection>

    <GallerySection title="Code block" description="FwCodeBlock uses the monospace stack, not the comparison font; this is for the surrounding text and the digits in code.">
      <FwCodeBlock :code="code" language="typescript" label="Dispatch retry" />
    </GallerySection>
  </div>
</template>
