// FIRST: @flowcatalyst/api-runtime sets Zod to jitless before any schema exists (strict CSP).
import { createApiRuntime, installSessionGuard } from "@flowcatalyst/api-runtime";
import { flowcatalystUi } from "@flowcatalyst/ui";
import ui from "@nuxt/ui/vue-plugin";
import { createApp } from "vue";
import { APPLICATION } from "./admin/icons.ts";
import { client } from "./api/tickets/client.gen.ts";
import * as sdk from "./api/tickets/sdk.gen.ts";
import App from "./App.vue";
import { router } from "./router.ts";
import "./main.css";

// Same origin, the cookie only; a 401 starts sign-in, a 403, a 5xx or an offline call is a toast.
const api = createApiRuntime({ clients: [client], signedOutUrl: "/signed-out" });
installSessionGuard(router, api.session, {
  notPermitted: () => "/not-permitted",
  onUnavailable: () => {
    void router.replace("/unavailable");
  },
});

createApp(App).use(router).use(ui).use(flowcatalystUi, { api, admin: { application: APPLICATION, api: sdk } }).mount("#app");
