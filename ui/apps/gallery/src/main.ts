// FIRST: @flowcatalyst/api-runtime sets Zod to jitless before any schema exists (strict CSP).
import { createApiRuntime } from "@flowcatalyst/api-runtime";
import ui from "@nuxt/ui/vue-plugin";
import { flowcatalystUi } from "@flowcatalyst/ui";
import { createApp } from "vue";
import App from "./App.vue";
import { applyComparisonFont, comparisonFontFor } from "./fonts.ts";
import { client } from "./api/orders-demo/client.gen.ts";
import { fakeFetch } from "./fake-server.ts";
import { router } from "./router.ts";
import "./main.css";

// The gallery has no backend: the generated orders client and the runtime's own auth client call an
// in-page fake server (src/fake-server.ts). The runtime is real: same-origin settings, 401 -> sign-in,
// failure toasts. Sign-out navigates inside the gallery.
client.setConfig({ fetch: fakeFetch });
const api = createApiRuntime({
  clients: [client],
  fetch: fakeFetch,
  navigate: (url) => {
    void router.push(url.startsWith("/auth/login") ? { path: "/signed-out", query: { from: url } } : url);
  },
  signedOutUrl: "/signed-out",
});

// The font comparison (dev server and `--mode fonts` only): `?font=<id>` swaps the font on <html>.
router.afterEach((to) => {
  void applyComparisonFont(comparisonFontFor(to.query["font"]));
});

createApp(App).use(router).use(ui).use(flowcatalystUi, { api }).mount("#app");
