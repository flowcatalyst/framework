// @flowcatalyst/ui: the shared UI package of the FlowCatalyst Java and Go frameworks
// (docs/go/ui.md). Consumed as TypeScript and Vue source; the application's Vite build compiles it.
export { flowcatalystUi } from "./plugin.ts";
export { useColorMode, type ColorMode, type ColorModePreference } from "./composables/useColorMode.ts";
export { default as FwColorModeButton } from "./components/FwColorModeButton.vue";
export { default as FwPageHeader } from "./components/FwPageHeader.vue";
export {
  COLOR_MODE_SNIPPET,
  COLOR_MODE_SNIPPET_SHA256,
  COLOR_MODE_STORAGE_KEY,
  strictContentSecurityPolicy,
  strictSecurityHeaders,
} from "./csp.ts";
