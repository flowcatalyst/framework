<script setup lang="ts">
// Cycles light -> dark -> system. Replaces Nuxt UI's UColorModeButton, which is unusable with
// `colorMode: false` (see useColorMode for why that is off).
import UButton from "@nuxt/ui/components/Button.vue";
import { computed } from "vue";
import { useColorMode, type ColorModePreference } from "../composables/useColorMode.ts";

const { preference } = useColorMode();

const next: Record<ColorModePreference, ColorModePreference> = {
  light: "dark",
  dark: "system",
  system: "light",
};

const icon = computed(() => {
  switch (preference.value) {
    case "light":
      return "i-lucide-sun";
    case "dark":
      return "i-lucide-moon";
    case "system":
      return "i-lucide-monitor";
  }
});

const label = computed(() => `Colour mode: ${preference.value}. Switch to ${next[preference.value]}`);

function cycle(): void {
  preference.value = next[preference.value];
}
</script>

<template>
  <UButton
    :icon="icon"
    :aria-label="label"
    color="neutral"
    variant="ghost"
    square
    data-testid="color-mode"
    @click="cycle"
  />
</template>
