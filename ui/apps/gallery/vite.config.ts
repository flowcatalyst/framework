import ui from "@nuxt/ui/vite";
import { flowcatalystUi, nuxtUiOptions } from "@flowcatalyst/ui/vite";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";

const options = nuxtUiOptions();

export default defineConfig({
  plugins: [vue(), ui(options), flowcatalystUi(options)],
  base: "/",
  build: { outDir: "dist", assetsDir: "assets", sourcemap: true },
  server: { port: 5174, strictPort: true },
});
