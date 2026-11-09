package io.flowcatalyst.framework.ui.kit;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/** The app shell's brand logo (app-shell.md, Brand): what the sidebar and top bar render. */
public final class ShellBrand {

  private ShellBrand() {}

  private static final int DEFAULT_HEIGHT = 32;

  /**
   * The logo height in CSS pixels.
   *
   * @param b the brand, or null
   * @return the brand's height, or 32
   */
  public static int height(Props.@Nullable Brand b) {
    return b != null && b.logoHeight() > 0 ? b.logoHeight() : DEFAULT_HEIGHT;
  }

  /**
   * Whether the brand replaces the bolt with an image.
   *
   * @param b the brand, or null
   * @return true when it has a logo URL or SVG
   */
  public static boolean hasImage(Props.@Nullable Brand b) {
    return b != null && (notEmpty(b.logoUrl()) || notEmpty(b.logoSvg()));
  }

  /**
   * The image's {@code src}: the checked URL, else the SVG as a base64 data URL the kit builds
   * (so the URL check, which refuses data:, does not apply to it).
   *
   * @param b the brand
   * @return the src
   */
  public static @Nullable String imageSrc(Props.Brand b) {
    String u = b.logoUrl();
    if (u != null && !u.isEmpty()) {
      return Markup.url(u);
    }
    String svg = b.logoSvg();
    if (svg == null) {
      svg = "";
    }
    return "data:image/svg+xml;base64,"
        + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The image's alt text.
   *
   * @param b the brand
   * @return the name, or "Logo"
   */
  public static String imageAlt(Props.Brand b) {
    String name = b.name();
    return name != null && !name.isEmpty() ? name : "Logo";
  }

  /**
   * The bolt's aria-label.
   *
   * @param b the brand, or null
   * @return the name, or "FlowCatalyst"
   */
  public static String boltLabel(Props.@Nullable Brand b) {
    String name = b == null ? null : b.name();
    return name != null && !name.isEmpty() ? name : "FlowCatalyst";
  }

  /**
   * The sidebar image's style: a URL image is capped at the logo height without being scaled up; an
   * SVG image fills the header link (kit.css), so it has none.
   *
   * @param b the brand
   * @return the style, or null for none
   */
  public static @Nullable String sidebarImageStyle(Props.Brand b) {
    return notEmpty(b.logoUrl()) ? "height: auto; max-height: " + height(b) + "px" : null;
  }

  private static boolean notEmpty(@Nullable String s) {
    return s != null && !s.isEmpty();
  }
}
