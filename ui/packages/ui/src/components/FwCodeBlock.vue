<script setup lang="ts">
// ui-kit code-block.md: source code or a document on a dark panel, highlighted, scrollable both ways,
// reachable by keyboard and named; a Copy button puts the text on the clipboard and says so in a
// polite live region. Highlighting is the package's tokenizer (src/lib/highlight.ts): every token is
// a text node, nothing is parsed as HTML.
import UButton from "@nuxt/ui/components/Button.vue";
import { computed, shallowRef } from "vue";
import { tokenize, type CodeLanguage, type TokenKind } from "../lib/highlight.ts";

const {
  code,
  label,
  language = "text",
  copyable = true,
} = defineProps<{
  code: string;
  /** The scroll region's accessible name (`Request body`, `Order schema`). */
  label: string;
  language?: CodeLanguage;
  copyable?: boolean;
}>();

const emit = defineEmits<{
  copied: [];
}>();

// The kit's highlight colours (ui-kit theme fc-components.css, .hl-*), on the kit's dark panel.
const KIND_CLASS: Readonly<Record<TokenKind, string>> = {
  key: "text-[#7dd3fc]",
  str: "text-[#86efac]",
  num: "text-[#fde68a]",
  kw: "text-[#c4b5fd]",
  type: "text-[#67e8f9]",
  var: "text-[#fca5a5]",
  comment: "text-[#94a3b8] italic",
  plain: "",
};

const tokens = computed(() => tokenize(code, language));
const status = shallowRef<"idle" | "copied" | "failed">("idle");
let reset: ReturnType<typeof setTimeout> | undefined;

async function copy(): Promise<void> {
  clearTimeout(reset);
  try {
    await navigator.clipboard.writeText(code);
    status.value = "copied";
    emit("copied");
  } catch {
    status.value = "failed";
  }
  reset = setTimeout(() => {
    status.value = "idle";
  }, 2000);
}
</script>

<template>
  <div class="relative" data-testid="fw-code-block">
    <pre
      tabindex="0"
      :aria-label="label"
      class="m-0 max-h-[60vh] min-h-48 overflow-auto whitespace-pre rounded-[6px] bg-[#1e293b] p-4 font-mono text-[13px] leading-[1.6] text-[#e2e8f0] [tab-size:2] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary dark:bg-[#0b1220] dark:shadow-[inset_0_0_0_1px_rgb(255_255_255/0.06)]"
    ><code><span v-for="(token, index) in tokens" :key="index" :class="KIND_CLASS[token.kind]">{{ token.text }}</span></code></pre>
    <div v-if="copyable" class="absolute top-2 right-2 flex items-center gap-2">
      <span class="sr-only" role="status" aria-live="polite">{{ status === "copied" ? "Copied to the clipboard" : status === "failed" ? "Could not copy" : "" }}</span>
      <UButton
        :icon="status === 'copied' ? 'i-lucide-check' : 'i-lucide-copy'"
        :label="status === 'copied' ? 'Copied' : status === 'failed' ? 'Copy failed' : 'Copy'"
        :aria-label="`Copy ${label}`"
        size="xs"
        color="neutral"
        variant="solid"
        data-testid="fw-code-block-copy"
        @click="copy"
      />
    </div>
  </div>
</template>
