// The list page and its record drawer agree on width through this key (docs/ui/u3.md, G5): the
// drawer is fixed to the right edge of the window, so the list cannot know it is there; FwListPage
// provides the host, FwRecordDrawer reports the width it takes while open, and the list makes room
// for it when the room is there (otherwise the drawer overlays the list, as before).
import type { InjectionKey, ShallowRef } from "vue";

export interface DrawerHost {
  /** The width in pixels of the open drawer, or undefined while none is open. */
  readonly width: ShallowRef<number | undefined>;
}

export const DRAWER_HOST_KEY: InjectionKey<DrawerHost> = Symbol("fw-drawer-host");

/** The list must keep at least this much (px) beside a drawer for the drawer to push it. */
export const MIN_LIST_WIDTH = 420;
