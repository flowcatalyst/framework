import ui from "@nuxt/ui/vite";
import { flowcatalystUi, nuxtUiOptions } from "@flowcatalyst/ui/vite";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";
import { ADMIN_ICONS } from "./src/admin/icons.ts";
import { pages } from "./src/pages.ts";

// Icons named in .ts data (the sidebar's) are not found by Nuxt UI's scan of .vue files: listed.
const options = nuxtUiOptions({ icons: [...pages.map((page) => page.icon), ...ADMIN_ICONS] });

// The build goes to the Java demo's web/dist, which its pom copies into the jar under fw-spa
// (static-assets contract section 8: Maven never runs Node).
// Development (contract section 9): Vite serves the SPA and proxies every API prefix, and the
// sign-in round trip, to the running demo (`ORDERS_BACKEND`, never port 8080). The demo's
// auth.public-base-url is then http://localhost:5175, so the cookie and the CSRF origin are the page's.
const backend = process.env["ORDERS_BACKEND"] ?? "http://127.0.0.1:18080";
export default defineConfig({
  plugins: [vue(), ui(options), flowcatalystUi(options)],
  base: "/",
  build: { outDir: "../../../examples/orders-demo/web/dist", emptyOutDir: true, assetsDir: "assets", sourcemap: false },
  server: {
    port: 5175,
    strictPort: true,
    proxy: {
      "/api": { target: backend },
      "/auth": { target: backend },
    },
  },
});
