// For tools without Vue support (typescript-eslint's type-aware lint): a .vue module that has no
// declaration file is a component. vue-tsc resolves .vue files itself and never uses this; Nuxt UI's
// components ship .d.vue.ts declarations, which win over it (allowArbitraryExtensions).
declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent;
  export default component;
}

/** True in the dev server and in `vite build --mode fonts` (vite.config.ts): the font comparison's fonts load. */
declare const __FONT_COMPARISON__: boolean;
