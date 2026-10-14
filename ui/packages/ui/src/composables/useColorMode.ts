import { useColorMode as useVueUseColorMode } from "@vueuse/core";
import { computed, type WritableComputedRef } from "vue";
import { COLOR_MODE_STORAGE_KEY } from "../csp.ts";

export type ColorModePreference = "light" | "dark" | "system";

export interface ColorMode {
  /** What the user chose; "system" follows the operating system. */
  readonly preference: WritableComputedRef<ColorModePreference>;
  /** The mode in effect now. */
  readonly value: Readonly<{ value: "light" | "dark" }>;
}

/**
 * Light, dark or system colour mode, stored under {@link COLOR_MODE_STORAGE_KEY} and applied as
 * `class="dark"` on `<html>` (the same switch as the kit's tokens and Nuxt UI's `dark:` variant).
 *
 * It replaces Nuxt UI's colour mode, which calls VueUse with `disableTransition: true`: that inserts
 * an inline `<style>` element on every switch, which a strict `style-src 'self'` refuses. The
 * pre-paint snippet in index.html reads the same key (src/csp.ts).
 */
export function useColorMode(): ColorMode {
  const mode = useVueUseColorMode({
    storageKey: COLOR_MODE_STORAGE_KEY,
    initialValue: "auto",
    disableTransition: false,
    modes: { light: "", dark: "dark" },
  });
  const preference = computed<ColorModePreference>({
    get: () => {
      const stored = mode.store.value;
      return stored === "auto" ? "system" : stored === "dark" ? "dark" : "light";
    },
    set: (next) => {
      mode.store.value = next === "system" ? "auto" : next;
    },
  });
  const value = computed<"light" | "dark">(() => (mode.state.value === "dark" ? "dark" : "light"));
  return { preference, value };
}
