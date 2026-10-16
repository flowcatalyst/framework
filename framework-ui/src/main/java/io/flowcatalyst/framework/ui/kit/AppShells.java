package io.flowcatalyst.framework.ui.kit;

/** The app shell's Datastar expressions (app-shell.md). */
public final class AppShells {

  private AppShells() {}

  /** The collapse button: toggles the signal on {@code <html>} and remembers it in a cookie. */
  public static final String COLLAPSE =
      "$sidebarCollapsed = !$sidebarCollapsed; document.cookie = 'fc_sidebar=' + ($sidebarCollapsed ? 'collapsed' : '') + ';path=/;max-age=31536000;samesite=lax'";

  /** Opens the narrow-screen sidebar and moves focus to its close button. */
  public static final String MOBILE_OPEN =
      "$sidebarMobileOpen = true; setTimeout(() => document.getElementById('fc-sidebar-close').focus())";

  /** Closes it and returns focus to the menu button. */
  public static final String MOBILE_CLOSE =
      "$sidebarMobileOpen = false; document.getElementById('fc-topbar-menu').focus()";

  /** Escape closes it, unless a dialog or popover is open above it. */
  public static final String MOBILE_ESCAPE =
      "evt.key === 'Escape' && $sidebarMobileOpen && !document.querySelector('dialog[open], [popover]:popover-open') && ($sidebarMobileOpen = false, document.getElementById('fc-topbar-menu').focus())";
}
