import "./zod-csp.ts";
import ui from "@nuxt/ui/vue-plugin";
import { flowcatalystUi } from "@flowcatalyst/ui";
import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router.ts";
import "./main.css";

createApp(App).use(router).use(ui).use(flowcatalystUi).mount("#app");
