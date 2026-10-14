<script setup lang="ts">
// ui-kit cursor-pager.md: "Newest / Newer / Older" paging for keyset-paginated lists, driven by
// useCursorPagination. A control that cannot be used is aria-disabled (it keeps focus, so a keyboard
// user who reached the last page is not thrown back to the top of the page).
import UButton from "@nuxt/ui/components/Button.vue";

const {
  summary,
  hasPrev,
  hasMore,
  loading = false,
} = defineProps<{
  /** "Page 2", "50 events", ... */
  summary?: string;
  /** A newer page exists in this session (useCursorPagination's hasPrev). */
  hasPrev: boolean;
  /** An older page exists (useCursorPagination's hasMore). */
  hasMore: boolean;
  loading?: boolean;
}>();

const emit = defineEmits<{
  newest: [];
  newer: [];
  older: [];
}>();

function go(enabled: boolean, event: "newest" | "newer" | "older"): void {
  if (!enabled || loading) {
    return;
  }
  switch (event) {
    case "newest":
      emit("newest");
      break;
    case "newer":
      emit("newer");
      break;
    case "older":
      emit("older");
      break;
  }
}
</script>

<template>
  <div class="flex flex-wrap items-center justify-between gap-2 border-t border-default px-3 py-2 text-[13px] text-muted" data-testid="fw-cursor-pager">
    <span>{{ summary }}</span>
    <nav class="flex items-center gap-2" aria-label="Pagination">
      <UButton
        label="Newest"
        icon="i-lucide-chevrons-left"
        size="sm"
        color="neutral"
        variant="outline"
        :aria-disabled="!hasPrev || loading"
        :class="{ 'cursor-not-allowed opacity-50': !hasPrev }"
        data-testid="fw-pager-newest"
        @click="go(hasPrev, 'newest')"
      />
      <UButton
        label="Newer"
        icon="i-lucide-chevron-left"
        size="sm"
        color="neutral"
        variant="outline"
        :aria-disabled="!hasPrev || loading"
        :class="{ 'cursor-not-allowed opacity-50': !hasPrev }"
        data-testid="fw-pager-newer"
        @click="go(hasPrev, 'newer')"
      />
      <UButton
        label="Older"
        trailing-icon="i-lucide-chevron-right"
        size="sm"
        color="neutral"
        variant="outline"
        :aria-disabled="!hasMore || loading"
        :class="{ 'cursor-not-allowed opacity-50': !hasMore }"
        data-testid="fw-pager-older"
        @click="go(hasMore, 'older')"
      />
    </nav>
  </div>
</template>
