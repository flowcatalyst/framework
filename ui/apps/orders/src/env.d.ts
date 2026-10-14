// For tools without Vue support (typescript-eslint's type-aware lint): a .vue module that has no
// declaration file is a component. vue-tsc resolves .vue files itself and never uses this; Nuxt UI's
// components ship .d.vue.ts declarations, which win over it (allowArbitraryExtensions).
declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent;
  export default component;
}
