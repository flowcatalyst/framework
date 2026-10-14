<script setup lang="ts">
import { FwCodeBlock, FwCodeChips, FwDetailField, FwDetailList, FwFormSection, FwJsonBlock, FwLocalTime, FwStatusBadge } from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import GallerySection from "../components/GallerySection.vue";

const schema = `{
  "type": "object",
  "properties": {
    "sku": { "type": "string", "pattern": "^[A-Z0-9][A-Z0-9-]*$", "maxLength": 64 },
    "quantity": { "type": "integer", "format": "int32", "minimum": 1 },
    "unitPriceCents": { "type": "integer", "format": "int64" },
    "gift": false,
    "note": null
  },
  "required": ["sku", "quantity", "unitPriceCents"]
}`;
const ts = `// Add a line through the generated SDK
import { addOrderLine } from "@/api/sdk.gen";
const { error } = await addOrderLine({ path: { orderId }, body: event.data });
if (error !== undefined) { applyServiceError(form, toServiceError(error, response)); }`;
const java = `static final Operation<AddLineBody, OrderView> ADD_LINE =
    Operation.post("/api/orders/{orderId}/lines", AddLineBody.V, OrderView.class)
        .id("addOrderLine").permission(MANAGE).build();`;
const payload = { id: "ord_1042", customer: "Initech", lines: [{ sku: "BOLT-M8", quantity: 2, unitPriceCents: 1999 }], tags: ["export"] };
</script>

<template>
  <div class="space-y-6">
    <UPageHeader title="Values and code" description="Page header, form sections, detail fields, local times, code, JSON and event codes">
      <template #links>
        <UButton label="Primary action" color="neutral" variant="outline" />
      </template>
    </UPageHeader>

    <GallerySection :behaviours="['one-h1']" title="Page header (UPageHeader)" description="This page's title block, Nuxt UI's page header themed compact (fcComponentTheme): the page's one h1, a description and actions on the right." />

    <GallerySection :behaviours="['section-headings', 'detail-fields']" title="Form section (FwFormSection) and detail fields (FwDetailList, FwDetailField)" description="A titled block with actions; label/value pairs in a description list, two columns that become one in a narrow container; an empty value reads '—'.">
      <FwFormSection title="Order" description="As placed by the customer." :level="3">
        <template #actions><UButton label="Edit" size="sm" color="neutral" variant="outline" /></template>
        <FwDetailList>
          <FwDetailField label="Customer" value="Initech" />
          <FwDetailField label="Status"><FwStatusBadge status="OPEN" :colors="{ OPEN: 'info' }" /></FwDetailField>
          <FwDetailField label="Placed"><FwLocalTime at="2026-10-06T08:30:00Z" /></FwDetailField>
          <FwDetailField label="Reference" />
          <FwDetailField label="Delivery note" value="Leave at the loading dock; ring twice. A long value wraps instead of overflowing its column." span />
        </FwDetailList>
      </FwFormSection>
      <FwFormSection title="Notes" card :level="3">
        <p class="text-sm">A carded section.</p>
      </FwFormSection>
      <FwFormSection title="Danger zone" danger :level="3" data-testid="danger-zone">
        <div class="flex items-center justify-between gap-3 text-sm">
          <p>Deleting an order removes its lines.</p>
          <UButton label="Delete order…" color="error" variant="outline" size="sm" />
        </div>
      </FwFormSection>
    </GallerySection>

    <GallerySection :behaviours="['local-time']" title="Local time (FwLocalTime)" description="The viewer's locale and time zone; UTC in the title; '-' when absent.">
      <ul class="space-y-1 text-sm">
        <li>Browser locale: <FwLocalTime at="2026-10-06T08:30:00Z" /></li>
        <li>Fixed (en-GB, Europe/Amsterdam): <FwLocalTime at="2026-10-06T08:30:00Z" locale="en-GB" time-zone="Europe/Amsterdam" data-testid="local-time-fixed" /></li>
        <li>Date only: <FwLocalTime at="2026-10-06T08:30:00Z" date-only locale="en-GB" time-zone="UTC" /></li>
        <li>Absent: <FwLocalTime /></li>
      </ul>
    </GallerySection>

    <GallerySection :behaviours="['code-copy']" title="Code block (FwCodeBlock)" description="Highlighted, scrollable both ways, focusable, named; Copy says so in a live region.">
      <FwCodeBlock :code="schema" language="json" label="AddLineBody schema" data-testid="code-json" />
      <FwCodeBlock :code="ts" language="typescript" label="Add a line in TypeScript" />
      <FwCodeBlock :code="java" language="java" label="The operation in Java" :copyable="false" />
    </GallerySection>

    <GallerySection :behaviours="['json-block']" title="JSON block (FwJsonBlock)" description="A payload, pretty-printed, light, unhighlighted.">
      <FwJsonBlock :value="payload" label="Order payload" data-testid="json-payload" />
    </GallerySection>

    <GallerySection :behaviours="['code-chips']" title="Code chips (FwCodeChips)" description="An event type's four segments; the text is the code.">
      <p><FwCodeChips code="orders:sales:order:placed" /></p>
      <p><FwCodeChips code="billing:invoice:issued" /></p>
    </GallerySection>
  </div>
</template>
