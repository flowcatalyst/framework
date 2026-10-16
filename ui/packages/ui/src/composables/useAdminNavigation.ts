// Loads the admin navigation once (docs/contracts/admin.md section 6): the answer of the application's
// generated `getAdminNavigation`. A failed load is a state, not an exception: the shell shows an inline
// error with Retry and an empty navigation, never a blank page.
import { ref, type Ref } from "vue";
import type { AdminNavigationView, AdminSectionView } from "../admin/types.ts";

export interface AdminNavigation {
  /** The sections the server lists for this caller; undefined until loaded and after a failure. */
  readonly sections: Ref<readonly AdminSectionView[] | undefined>;
  readonly loading: Ref<boolean>;
  readonly failed: Ref<boolean>;
  /** Loads (again). Resolves when the state is settled; never rejects. */
  reload(): Promise<void>;
}

export function useAdminNavigation(load: () => Promise<AdminNavigationView>): AdminNavigation {
  const sections = ref<readonly AdminSectionView[] | undefined>(undefined);
  const loading = ref(false);
  const failed = ref(false);
  let latest = 0;
  async function reload(): Promise<void> {
    const mine = ++latest;
    loading.value = true;
    failed.value = false;
    try {
      const answer = await load();
      if (mine === latest) {
        sections.value = answer.sections;
      }
    } catch {
      if (mine === latest) {
        sections.value = undefined;
        failed.value = true;
      }
    } finally {
      if (mine === latest) {
        loading.value = false;
      }
    }
  }
  void reload();
  return { sections, loading, failed, reload };
}
