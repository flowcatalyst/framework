// A record drawer as a CHILD ROUTE of its list (the platform SPA's useDrawerRoute): the drawer has
// its own URL (deep links work), Back closes it, the list's filter query survives the round trip.
// Leaving while the drawer's form is dirty asks "Discard changes?", and so does switching to another
// row (a same-route param change, which the leave guard does not see).
//
//   // routes: { path: "/orders", component: OrdersList, children: [{ path: ":id", component: OrderDrawer }] }
//   const { id, close, replaceToDetail } = useDrawerRoute({ listPath: "/orders", dirty });
import { computed, type ComputedRef, type Ref } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter, type LocationQuery } from "vue-router";
import { confirmDiscardChanges, useConfirm } from "./useConfirm.ts";

export interface DrawerRouteOptions {
  /** The list route the drawer sits over (`/orders`). */
  readonly listPath: string;
  /** The route param holding the record key (default `id`). */
  readonly param?: string;
  /** While true, leaving or switching rows asks to discard changes. */
  readonly dirty?: Readonly<Ref<boolean>>;
  /** Drawer-only query parameters dropped on the way back to the list (default `edit`, `tab`). */
  readonly drawerQuery?: readonly string[];
}

export interface DrawerRoute {
  /** The record key from the route (the drawer component is reused across rows). */
  readonly id: ComputedRef<string | undefined>;
  /** Closes the drawer: back to the list with its filters (the discard question when dirty). */
  close(): Promise<void>;
  /** After creating a record: show it, replacing the spent create route in history. */
  replaceToDetail(id: string): Promise<void>;
}

export function useDrawerRoute(options: DrawerRouteOptions): DrawerRoute {
  const route = useRoute();
  const router = useRouter();
  const confirm = useConfirm();
  const param = options.param ?? "id";
  const strip = new Set(options.drawerQuery ?? ["edit", "tab"]);
  // A close the drawer already confirmed (or that needed no confirmation) passes the guards once.
  let approved = false;

  const id = computed(() => {
    const raw = route.params[param];
    return typeof raw === "string" ? raw : undefined;
  });

  function listQuery(): LocationQuery {
    return Object.fromEntries(Object.entries(route.query).filter(([key]) => !strip.has(key)));
  }

  const ask = (): Promise<boolean> | true => (approved || options.dirty?.value !== true ? true : confirmDiscardChanges(confirm));

  onBeforeRouteLeave(async () => {
    const ok = await ask();
    approved = false;
    return ok;
  });

  onBeforeRouteUpdate(async (to, from) => {
    if (to.params[param] === from.params[param]) {
      return true;
    }
    const ok = await ask();
    approved = false;
    return ok;
  });

  return {
    id,
    close: async () => {
      if (!(await ask())) {
        return;
      }
      approved = true;
      await router.push({ path: options.listPath, query: listQuery() });
    },
    replaceToDetail: async (next) => {
      approved = true;
      await router.replace({ path: `${options.listPath}/${encodeURIComponent(next)}`, query: listQuery() });
    },
  };
}
