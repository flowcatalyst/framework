<script setup lang="ts">
// ui-kit local-time.md: an instant as `<time datetime>`, shown in the viewer's locale and time zone,
// with the UTC text in the title. Not an instant (or absent): the `empty` text (default "-").
import { computed } from "vue";
import { localText, utcText } from "../lib/time.ts";

const {
  at,
  dateOnly = false,
  empty = "-",
  locale,
  timeZone,
} = defineProps<{
  /** RFC 3339 instant (`2026-10-06T08:30:00Z`), as the API sends `date-time` values. */
  at?: string | undefined;
  dateOnly?: boolean;
  empty?: string;
  /** BCP 47 locale; default the browser's. */
  locale?: string;
  /** IANA time zone; default the browser's. */
  timeZone?: string;
}>();

const utc = computed(() => (at === undefined ? undefined : utcText(at, dateOnly)));
const text = computed(() =>
  at === undefined
    ? undefined
    : localText(at, {
        dateOnly,
        ...(locale === undefined ? {} : { locale }),
        ...(timeZone === undefined ? {} : { timeZone }),
      }),
);
</script>

<template>
  <time v-if="at !== undefined && utc !== undefined" :datetime="at" :title="utc" data-testid="fw-local-time">{{ text }}</time>
  <span v-else data-testid="fw-local-time-empty">{{ empty }}</span>
</template>
