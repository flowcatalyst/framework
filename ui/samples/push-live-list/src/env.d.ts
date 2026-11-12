// A .vue module is a component, for tools without Vue support (ui/packages/ui/src/env.d.ts says why).
declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent;
  export default component;
}
