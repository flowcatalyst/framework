import ui from "@nuxt/ui/vite";
import { flowcatalystUi, nuxtUiOptions } from "@flowcatalyst/ui/vite";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";
import { pages } from "./src/pages.ts";

// Icons named in .ts data (the sidebar's) are not found by Nuxt UI's scan of .vue files: listed.
const options = nuxtUiOptions({ icons: [...pages.map((page) => page.icon), "i-lucide-shield"] });

export default defineConfig({
  plugins: [vue(), ui(options), flowcatalystUi(options)],
  base: "/",
  build: { outDir: "dist", assetsDir: "assets", sourcemap: true },
  server: { port: 5174, strictPort: true },
});
