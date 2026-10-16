package io.flowcatalyst.framework.ui.kit;

import gg.jte.Content;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The kit's components as functions: each returns a lazy {@link Content} that writes the
 * component's markup (the specs in {@code ui-kit/components}; the fixtures in {@code
 * ui-kit/fixtures} are what the tests compare with). Compose them in your own JTE templates
 * ({@code ${Kit.badge(new Props.Badge("success", null, null, null), Markup.text("Active"))}}) or
 * write them to a page with {@link io.flowcatalyst.framework.ui.View}.
 *
 * <p>Props are records named as the spec's Props tables; an absent prop is {@code null} (or
 * {@code false}). A slot is a {@link Content}; build one with {@link Markup#text}, {@link
 * Markup#raw} (trusted markup only) or, in a JTE template, a content block. Text and attribute
 * values are escaped by the templates; ids, signal names and paths that end up in Datastar
 * expressions are validated and refused when they are not plain ({@link Markup}).
 */
public final class Kit {

  private Kit() {}

  // The engine loads the classes the build generated; there is no compiler and no template source
  // on the production classpath.
  private static final TemplateEngine ENGINE = TemplateEngine.createPrecompiled(ContentType.Html);

  private static final ScopedValue<String> SPRITE_URL = ScopedValue.newInstance();

  /**
   * Runs {@code body} with the icon sprite URL icons reference (a page puts the served, hashed URL
   * here). Content is lazy: render inside the call.
   *
   * @param <T> the result type
   * @param url the sprite URL
   * @param body the rendering
   * @return what the body returns
   * @throws Exception what the body throws
   */
  public static <T> T withSpriteUrl(String url, Callable<T> body) throws Exception {
    return ScopedValue.where(SPRITE_URL, url).call(body::call);
  }

  /**
   * The sprite reference for a Lucide icon name ({@code "x"}, {@code "search"}).
   *
   * @param name the icon name without the {@code i-} prefix
   * @return {@code <sprite>#i-<name>}
   */
  public static String iconHref(String name) {
    String base = SPRITE_URL.isBound() ? SPRITE_URL.get() : Markup.DEFAULT_SPRITE_URL;
    return base + "#i-" + name;
  }

  static Content component(String template, Map<String, Object> params) {
    return output -> ENGINE.render("kit/" + template + ".jte", params, output);
  }

  static Map<String, Object> params(Object p, @Nullable Object... slots) {
    Map<String, Object> m = new HashMap<>();
    m.put("p", p);
    for (int i = 0; i < slots.length; i += 2) {
      if (slots[i + 1] != null) {
        m.put((String) slots[i], slots[i + 1]);
      }
    }
    return m;
  }

  /**
   * A Lucide icon from the sprite (decorative unless a label is given).
   *
   * @param name the icon name
   * @param cls classes for the svg, or null
   * @param label the accessible name of a meaningful icon, or null for a decorative one
   * @return the component
   */
  public static Content icon(String name, @Nullable String cls, @Nullable String label) {
    Map<String, Object> m = new HashMap<>();
    m.put("name", name);
    m.put("cls", cls);
    m.put("label", label);
    return component("icon", m);
  }

  // ---- display, buttons, forms

  /**
   * badge component.
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content badge(Props.Badge p, Content body) {
    return component("badge", params(p, "body", body));
  }

  /**
   * tag component.
   *
   * @param p props
   * @return the component
   */
  public static Content tag(Props.Tag p) {
    return component("tag", params(p));
  }

  /**
   * alert component.
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content alert(Props.Alert p, @Nullable Content body) {
    return component("alert", params(p, "body", body));
  }

  /**
   * flash-banner component.
   *
   * @param p props
   * @return the component
   */
  public static Content flashBanner(Props.FlashBanner p) {
    return component("flash_banner", params(p));
  }

  /**
   * flash-message component.
   *
   * @param p props
   * @return the component
   */
  public static Content flashMessage(Props.FlashMessage p) {
    return component("flash_message", params(p));
  }

  /**
   * flash-stack component.
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content flashStack(Props.FlashStack p, @Nullable Content body) {
    return component("flash_stack", params(p, "body", body));
  }

  /**
   * spinner component.
   *
   * @param p props
   * @return the component
   */
  public static Content spinner(Props.Spinner p) {
    return component("spinner", params(p));
  }

  /**
   * skeleton component.
   *
   * @param p props
   * @return the component
   */
  public static Content skeleton(Props.Skeleton p) {
    return component("skeleton", params(p));
  }

  /**
   * breadcrumb component.
   *
   * @param p props
   * @return the component
   */
  public static Content breadcrumb(Props.Breadcrumb p) {
    return component("breadcrumb", params(p));
  }

  /**
   * empty-state component.
   *
   * @param p props
   * @return the component
   */
  public static Content emptyState(Props.EmptyState p) {
    return component("empty_state", params(p));
  }

  /**
   * page-header component.
   *
   * @param p props
   * @param actions the actions slot
   * @return the component
   */
  public static Content pageHeader(Props.PageHeader p, @Nullable Content actions) {
    return component("page_header", params(p, "actions", actions));
  }

  /**
   * button component.
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content button(Props.Button p, @Nullable Content body) {
    return component("button", params(p, "body", body));
  }

  /**
   * legacy-button component.
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content legacyButton(Props.LegacyButton p, @Nullable Content body) {
    return component("legacy_button", params(p, "body", body));
  }

  /**
   * icon-button component.
   *
   * @param p props
   * @return the component
   */
  public static Content iconButton(Props.IconButton p) {
    return component("icon_button", params(p));
  }

  /**
   * input component.
   *
   * @param p props
   * @return the component
   */
  public static Content input(Props.Input p) {
    return component("input", params(p));
  }

  /**
   * textarea component.
   *
   * @param p props
   * @return the component
   */
  public static Content textarea(Props.Textarea p) {
    return component("textarea", params(p));
  }

  /**
   * select component.
   *
   * @param p props
   * @return the component
   */
  public static Content select(Props.Select p) {
    return component("select", params(p));
  }

  /**
   * checkbox component.
   *
   * @param p props
   * @return the component
   */
  public static Content checkbox(Props.Checkbox p) {
    return component("checkbox", params(p));
  }

  /**
   * switch-control component.
   *
   * @param p props
   * @return the component
   */
  public static Content switchControl(Props.Switch p) {
    return component("switch", params(p));
  }

  /**
   * label component.
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content label(Props.Label p, @Nullable Content body) {
    return component("label", params(p, "body", body));
  }

  /**
   * field component.
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content field(Props.Field p, @Nullable Content body) {
    return component("field", params(p, "body", body));
  }

  /**
   * field-group component.
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content fieldGroup(Props.FieldGroup p, @Nullable Content body) {
    return component("field_group", params(p, "body", body));
  }

  /**
   * form-field component.
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content formField(Props.FormField p, @Nullable Content body) {
    return component("form_field", params(p, "body", body));
  }

  /**
   * search-input component.
   *
   * @param p props
   * @return the component
   */
  public static Content searchInput(Props.SearchInput p) {
    return component("search_input", params(p));
  }

  /**
   * filter-select component.
   *
   * @param p props
   * @return the component
   */
  public static Content filterSelect(Props.FilterSelect p) {
    return component("filter_select", params(p));
  }

  /**
   * toggle-group component.
   *
   * @param p props
   * @return the component
   */
  public static Content toggleGroup(Props.ToggleGroup p) {
    return component("toggle_group", params(p));
  }
}
