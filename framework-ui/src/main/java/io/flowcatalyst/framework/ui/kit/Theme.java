package io.flowcatalyst.framework.ui.kit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The theme and shell attributes app-shell.md puts on {@code <html>}, and the theme picker's
 * options. Dark mode is a {@code dark} class on {@code <html>} rendered by the server from the
 * {@value #THEME_COOKIE} and {@value #SCHEME_COOKIE} cookies, so a dark page does not flash light
 * before Datastar runs.
 */
public final class Theme {

  private Theme() {}

  /** Holds an explicit choice ({@code light} or {@code dark}; absent is system). */
  public static final String THEME_COOKIE = "fc_theme";

  /** Holds the system scheme the page last observed ({@code light} or {@code dark}). */
  public static final String SCHEME_COOKIE = "fc_scheme";

  /** Holds {@code collapsed} when the user collapsed the sidebar. */
  public static final String SIDEBAR_COOKIE = "fc_sidebar";

  private static final String COOKIE_TAIL = ";path=/;max-age=31536000;samesite=lax";

  private static final String WATCH_EXPR =
      "const m = window.matchMedia('(prefers-color-scheme: dark)'); "
          + "const sync = () => { if ($theme !== 'system') return; $dark = m.matches; document.cookie = 'fc_scheme=' + (m.matches ? 'dark' : 'light') + '"
          + COOKIE_TAIL
          + "' }; "
          + "sync(); m.addEventListener('change', sync)";

  /**
   * One button of the theme picker.
   *
   * @param choice system, light or dark
   * @param label the text
   * @param icon the icon
   * @param click the click handler
   */
  public record Choice(String choice, String label, String icon, String click) {

    /**
     * The initial aria-pressed value.
     *
     * @param current the user's choice
     * @return true or false as text
     */
    public String pressed(@Nullable String current) {
      return choice.equals(normalize(current)) ? "true" : "false";
    }

    /**
     * The aria-pressed binding.
     *
     * @return the expression
     */
    public String pressedExpr() {
      return "$theme === '" + choice + "' ? 'true' : 'false'";
    }
  }

  private static final List<Choice> CHOICES =
      List.of(
          new Choice(
              "system",
              "System",
              "monitor",
              "$theme = 'system'; $dark = window.matchMedia('(prefers-color-scheme: dark)').matches; document.cookie = 'fc_theme=;path=/;max-age=0;samesite=lax'; document.cookie = 'fc_scheme=' + ($dark ? 'dark' : 'light') + '"
                  + COOKIE_TAIL
                  + "'"),
          new Choice(
              "light",
              "Light",
              "sun",
              "$theme = 'light'; $dark = false; document.cookie = 'fc_theme=light" + COOKIE_TAIL + "'"),
          new Choice(
              "dark",
              "Dark",
              "moon",
              "$theme = 'dark'; $dark = true; document.cookie = 'fc_theme=dark" + COOKIE_TAIL + "'"));

  /**
   * The picker's buttons.
   *
   * @return the choices
   */
  public static List<Choice> choices() {
    return CHOICES;
  }

  /**
   * A theme choice, system for anything unknown.
   *
   * @param choice system, light or dark
   * @return the choice
   */
  public static String normalize(@Nullable String choice) {
    return "light".equals(choice) ? "light" : "dark".equals(choice) ? "dark" : "system";
  }

  /**
   * The theme attributes of {@code <html>}: the class rendered by the server, the two signals
   * (theme is the choice, dark is what is shown), the class binding and the system-scheme watcher.
   *
   * @param choice system, light or dark
   * @param dark whether the page renders dark
   * @return the attributes
   */
  public static Map<String, Object> htmlAttrs(@Nullable String choice, boolean dark) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("data-signals:theme", "'" + normalize(choice) + "'");
    a.put("data-signals:dark", dark ? "true" : "false");
    a.put("data-class:dark", "$dark");
    a.put("data-init", WATCH_EXPR);
    if (dark) {
      a.put("class", "dark");
    }
    return a;
  }

  /**
   * The shell attributes of {@code <html>}: the collapse signal (initial value from the {@value
   * #SIDEBAR_COOKIE} cookie) and the class it drives, and the narrow-screen sidebar's signal and
   * class (closed on load).
   *
   * @param collapsed whether the sidebar starts collapsed
   * @return the attributes
   */
  public static Map<String, Object> shellHtmlAttrs(boolean collapsed) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("data-signals:sidebar-collapsed", collapsed ? "true" : "false");
    a.put("data-class:fc-collapsed", "$sidebarCollapsed");
    a.put("data-signals:sidebar-mobile-open", "false");
    a.put("data-class:fc-mobile-open", "$sidebarMobileOpen");
    return a;
  }
}
