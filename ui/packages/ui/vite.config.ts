// Used by vitest and by scripts/nuxt-ui-types.ts. The package has no build output of its own:
// applications compile its source.
import ui from "@nuxt/ui/vite";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";
import { flowcatalystUi, nuxtUiOptions } from "./src/vite.ts";

const options = nuxtUiOptions();

export default defineConfig({
  plugins: [vue(), ui(options), flowcatalystUi(options)],
  test: {
    environment: "happy-dom",
    include: ["test/**/*.test.ts"],
  },
});
