import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";

// Development: Vite serves the page and proxies /api to a running go/examples/push-demo, so the page
// and its API share an origin (the subscribe goes through the page's own HTTP). `PUSH_DEMO_BACKEND`
// overrides the demo's first port (default 18100).
const backend = process.env["PUSH_DEMO_BACKEND"] ?? "http://127.0.0.1:18100";

export default defineConfig({
  plugins: [vue()],
  build: { outDir: "dist", emptyOutDir: true },
  server: { port: 5177, strictPort: true, proxy: { "/api": { target: backend } } },
});
