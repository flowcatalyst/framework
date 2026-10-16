import ui from "@nuxt/ui/vite";
import { flowcatalystUi, nuxtUiOptions } from "@flowcatalyst/ui/vite";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";
import { ADMIN_ICONS } from "./src/admin/icons.ts";
import { pages } from "./src/pages.ts";

// Icons named in .ts data (the sidebar's) are not found by Nuxt UI's scan of .vue files: listed.
const options = nuxtUiOptions({ icons: [...pages.map((page) => page.icon), "i-lucide-shield", ...ADMIN_ICONS] });

// The font comparison's fonts (src/fonts.ts) load in the dev server and in `--mode fonts` (built to
// dist-fonts/ for `pnpm gallery:fonts`); the default build, whose dist/ the licence notices are
// measured on, does not contain them.
export default defineConfig(({ command, mode }) => ({
  define: { __FONT_COMPARISON__: JSON.stringify(command === "serve" || mode === "fonts") },
  plugins: [vue(), ui(options), flowcatalystUi(options)],
  base: "/",
  build: { outDir: mode === "fonts" ? "dist-fonts" : "dist", assetsDir: "assets", sourcemap: true },
  server: { port: 5174, strictPort: true },
}));
