// Writes Nuxt UI's generated theme templates (node_modules/.nuxt-ui, the `#build/ui` alias) for this
// package, so vue-tsc can type Nuxt UI's component props. Nuxt UI writes them from its Vite plugin's
// `config` hook; resolving the Vite config runs that hook without building anything.
import { resolveConfig } from "vite";

await resolveConfig({ configFile: "vite.config.ts", logLevel: "warn" }, "build");
console.log("Nuxt UI templates written to node_modules/.nuxt-ui");
