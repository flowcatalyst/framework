package io.flowcatalyst.framework.ui.kit;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Class lists of the specs that depend on an option. Classes are concatenated, never merged. */
public final class Classes {

  private Classes() {}

  /** button.md BASE (also used by pagination links). */
  public static final String BUTTON_BASE = "inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50";

  private static final Map<String, String> BUTTON_VARIANTS =
      Map.of(
          "primary", "border-transparent bg-primary text-primary-foreground shadow-xs hover:bg-primary/90 active:bg-primary/80",
          "secondary", "border-transparent bg-foreground/5 text-foreground shadow-xs hover:bg-foreground/10 active:bg-foreground/15",
          "outline", "border-border text-foreground hover:bg-foreground/5 active:bg-foreground/10",
          "ghost", "border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10",
          "destructive", "border-transparent bg-destructive text-destructive-foreground shadow-xs hover:bg-destructive/90 active:bg-destructive/80",
          "text", "border-transparent text-primary hover:bg-primary/10 active:bg-primary/15",
          "destructive-outline", "border-destructive text-destructive hover:bg-destructive/5 active:bg-destructive/10",
          "success-outline", "border-success text-success hover:bg-success/5 active:bg-success/10");

  private static final Map<String, String> BUTTON_SIZES =
      Map.of(
          "sm", "h-8 gap-1.5 rounded-md px-3",
          "md", "h-9 gap-2 rounded-lg px-4",
          "lg", "h-10 gap-2 rounded-lg px-5",
          "icon", "size-9 rounded-lg");

  private static final Map<String, String> BADGE_VARIANTS =
      Map.of(
          "primary", "border-transparent bg-primary text-primary-foreground",
          "secondary", "border-transparent bg-foreground/5 text-foreground",
          "outline", "border-border text-foreground",
          "destructive", "border-transparent bg-destructive text-destructive-foreground",
          "success", "border-transparent bg-success text-success-foreground",
          "info", "border-transparent bg-info text-info-foreground",
          "warn", "border-transparent bg-warning text-warning-foreground");

  private static final Map<String, String> ALERT_VARIANTS =
      Map.of(
          "neutral", "border-border text-foreground",
          "destructive", "border-destructive/50 text-destructive",
          "warn", "border-warning/50 text-warning");

  private static final Map<String, String> TOGGLE_SIZES =
      Map.of(
          "sm", "h-8 gap-1.5 rounded-md px-2",
          "md", "h-9 gap-2 rounded-lg px-3",
          "lg", "h-10 gap-2 rounded-lg px-4");

  private static final Map<String, String> FIELD_ORIENTATIONS =
      Map.of(
          "vertical", "flex-col gap-2",
          "horizontal", "flex-row items-center gap-3 [&>[data-slot=field-label]]:flex-1",
          "responsive", "flex-col gap-2 @md/field-group:flex-row @md/field-group:items-start @md/field-group:gap-4 @md/field-group:[&>[data-slot=field-label]]:w-1/3 @md/field-group:[&>[data-slot=field-label]]:shrink-0");

  private static String pick(Map<String, String> map, @Nullable String key, String def, String what) {
    String k = key == null || key.isEmpty() ? def : key;
    String v = map.get(k);
    if (v == null) {
      throw new IllegalArgumentException("unknown " + what + ": " + Markup.escape(k));
    }
    return v;
  }

  /**
   * BASE + variant + size (button.md).
   *
   * @param variant the variant, null for primary
   * @param size the size, null for md
   * @return the classes
   */
  public static String button(@Nullable String variant, @Nullable String size) {
    return Markup.cx(
        BUTTON_BASE,
        pick(BUTTON_VARIANTS, variant, "primary", "button variant"),
        pick(BUTTON_SIZES, size, "md", "button size"));
  }

  /**
   * The badge variant's classes.
   *
   * @param variant the variant, null for primary
   * @return the classes
   */
  public static String badgeVariant(@Nullable String variant) {
    return pick(BADGE_VARIANTS, variant, "primary", "badge variant");
  }

  /**
   * The alert variant's classes.
   *
   * @param variant the variant, null for neutral
   * @return the classes
   */
  public static String alertVariant(@Nullable String variant) {
    return pick(ALERT_VARIANTS, variant, "neutral", "alert variant");
  }

  /**
   * The toggle size's classes.
   *
   * @param size the size, null for md
   * @return the classes
   */
  public static String toggleSize(@Nullable String size) {
    return pick(TOGGLE_SIZES, size, "md", "toggle size");
  }

  /**
   * The field orientation's classes.
   *
   * @param orientation the orientation, null for vertical
   * @return the classes
   */
  public static String fieldOrientation(@Nullable String orientation) {
    return pick(FIELD_ORIENTATIONS, orientation, "vertical", "field orientation");
  }

  /**
   * An enumerated option checked against its allowed values.
   *
   * @param value the value, null or empty for the default
   * @param def the default
   * @param what the option's name, for the message
   * @param allowed the allowed values
   * @return the value or the default
   */
  public static String oneOf(@Nullable String value, String def, String what, String... allowed) {
    if (value == null || value.isEmpty()) {
      return def;
    }
    for (String a : allowed) {
      if (a.equals(value)) {
        return value;
      }
    }
    throw new IllegalArgumentException("unknown " + what + ": " + Markup.escape(value));
  }
}
