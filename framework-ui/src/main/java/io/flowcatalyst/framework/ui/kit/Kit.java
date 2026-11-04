package io.flowcatalyst.framework.ui.kit;

import gg.jte.Content;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import io.flowcatalyst.framework.ui.i18n.Messages;
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

  // ---- messages (docs/contracts/i18n.md)

  /**
   * The kit message for a key in the current rendering's language ({@link Messages#current()}).
   * Templates call this for every user-visible text; a prop that sets the text wins before it.
   *
   * @param key a {@code kit.} key
   * @return the text
   */
  public static String t(String key) {
    return Messages.current().t(key);
  }

  /**
   * As {@link #t(String)} with one argument.
   *
   * @param key a {@code kit.} key
   * @param name the placeholder name
   * @param value its value; null is the empty text
   * @return the text
   */
  public static String t(String key, String name, @Nullable Object value) {
    return Messages.current().t(key, Map.of(name, value == null ? "" : value));
  }

  /**
   * The locale of the current rendering ({@link Messages#current()}): what {@code <html lang>}
   * carries.
   *
   * @return a BCP 47 tag
   */
  public static String locale() {
    return Messages.current().locale();
  }

  /**
   * Quotes text as a JavaScript single-quoted string literal, for a message that goes into a
   * Datastar expression.
   *
   * @param s the text
   * @return the literal
   */
  public static String jsq(String s) {
    StringBuilder sb = new StringBuilder("'");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '\'' -> sb.append("\\'");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\u2028' -> sb.append("\\u2028");
        case '\u2029' -> sb.append("\\u2029");
        default -> sb.append(c);
      }
    }
    return sb.append('\'').toString();
  }

  /**
   * The JavaScript expression for a message whose placeholder {@code arg} is the JavaScript
   * expression {@code expr}: the message's text as quoted string terms joined with {@code +} around
   * the expression, empty terms left out ({@code "Remove {label}"} with {@code t.textContent} is
   * {@code 'Remove ' + t.textContent}).
   *
   * @param key a {@code kit.} key
   * @param arg the placeholder name
   * @param expr the JavaScript expression
   * @return the expression
   */
  public static String jsMessage(String key, String arg, String expr) {
    String mark = "\u0000";
    String[] parts = Messages.current().t(key, Map.of(arg, mark)).split(mark, -1);
    java.util.List<String> terms = new java.util.ArrayList<>();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        terms.add(expr);
      }
      if (!parts[i].isEmpty()) {
        terms.add(jsq(parts[i]));
      }
    }
    return terms.isEmpty() ? "''" : String.join(" + ", terms);
  }

  /**
   * A message written as markup: the text is escaped, and each placeholder named in {@code raw} is
   * replaced by that trusted markup (which the caller built from escaped values).
   *
   * @param key a {@code kit.} key
   * @param raw placeholder name to trusted markup
   * @return the markup content
   */
  public static Content htmlMessage(String key, Map<String, String> raw) {
    String mark = "\u0000";
    Map<String, String> args = new HashMap<>();
    raw.keySet().forEach(n -> args.put(n, mark + n + mark));
    String text = Markup.escape(Messages.current().t(key, args));
    for (Map.Entry<String, String> e : raw.entrySet()) {
      text = text.replace(mark + e.getKey() + mark, e.getValue());
    }
    return Markup.raw(text);
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

  // ---- tables, lists, tabs, time

  /**
   * table component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content table(Props.Table p, @Nullable Content body) {
    return component("table", params(p, "body", body));
  }

  /**
   * tableRow component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content tableRow(Props.TR p, @Nullable Content body) {
    return component("table_row", params(p, "body", body));
  }

  /**
   * tableHead component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content tableHead(Props.TH p, @Nullable Content body) {
    return component("table_head", params(p, "body", body));
  }

  /**
   * tableCell component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content tableCell(Props.TD p, @Nullable Content body) {
    return component("table_cell", params(p, "body", body));
  }

  /**
   * sortableHeader component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content sortableHeader(Props.SortableHeader p) {
    return component("sortable_header", params(p));
  }

  /**
   * emptyRow component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content emptyRow(Props.EmptyRow p) {
    return component("empty_row", params(p));
  }

  /**
   * dataTable component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content dataTable(Props.DataTable p, @Nullable Content body) {
    return component("data_table", params(p, "body", body));
  }

  /**
   * rowActions component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content rowActions(Props.RowActions p) {
    return component("row_actions", params(p));
  }

  /**
   * tableToolbar component (see its spec).
   *
   * @param p props
   * @param filters the filters slot
   * @return the component
   */
  public static Content tableToolbar(Props.TableToolbar p, @Nullable Content filters) {
    return component("table_toolbar", params(p, "filters", filters));
  }

  /**
   * tableToolbarState component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content tableToolbarState(Props.TableToolbarState p) {
    return component("table_toolbar_state", params(p));
  }

  /**
   * pagination component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content pagination(Props.Pagination p) {
    return component("pagination", params(p));
  }

  /**
   * cursorPager component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content cursorPager(Props.CursorPager p) {
    return component("cursor_pager", params(p));
  }

  /**
   * paginator component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content paginator(Props.Paginator p) {
    return component("paginator", params(p));
  }

  /**
   * localTime component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content localTime(Props.LocalTime p) {
    return component("local_time", params(p));
  }

  /**
   * localNumber component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content localNumber(Props.LocalNumber p) {
    return component("local_number", params(p));
  }

  /**
   * localMoney component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content localMoney(Props.LocalMoney p) {
    return component("local_money", params(p));
  }

  /**
   * localQuantity component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content localQuantity(Props.LocalQuantity p) {
    return component("local_quantity", params(p));
  }

  /**
   * dateRange component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content dateRange(Props.DateRange p) {
    return component("date_range", params(p));
  }

  /**
   * tabs.md: client tabs; the panels are the slots, by tab value.
   *
   * @param p props
   * @param panels each tab's content by value
   * @return the component
   */
  public static Content tabs(Props.Tabs p, Map<String, Content> panels) {
    return component("tabs", params(p, "panels", panels));
  }

  // ---- overlays, forms, code, shell

  /**
   * dialog component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @param footer the footer slot
   * @return the component
   */
  public static Content dialog(Props.Dialog p, @Nullable Content body, @Nullable Content footer) {
    return component("dialog", params(p, "body", body, "footer", footer));
  }

  /**
   * confirmDialog component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content confirmDialog(Props.ConfirmDialog p) {
    return component("confirm_dialog", params(p));
  }

  /**
   * sheet component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @param footer the footer slot
   * @return the component
   */
  public static Content sheet(Props.Sheet p, @Nullable Content body, @Nullable Content footer) {
    return component("sheet", params(p, "body", body, "footer", footer));
  }

  /**
   * drawer component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @param extras the extras slot
   * @param footer the footer slot
   * @return the component
   */
  public static Content drawer(Props.Drawer p, @Nullable Content body, @Nullable Content extras, @Nullable Content footer) {
    return component("drawer", params(p, "body", body, "extras", extras, "footer", footer));
  }

  /**
   * drawerContent component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @param extras the extras slot
   * @param footer the footer slot
   * @return the component
   */
  public static Content drawerContent(Props.Drawer p, @Nullable Content body, @Nullable Content extras, @Nullable Content footer) {
    return component("drawer_content", params(p, "body", body, "extras", extras, "footer", footer));
  }

  /**
   * formDialog component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content formDialog(Props.FormDialog p, @Nullable Content body) {
    return component("form_dialog", params(p, "body", body));
  }

  /**
   * formSection component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @param actions the actions slot
   * @return the component
   */
  public static Content formSection(Props.FormSection p, @Nullable Content body, @Nullable Content actions) {
    return component("form_section", params(p, "body", body, "actions", actions));
  }

  /**
   * autocomplete component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content autocomplete(Props.Autocomplete p) {
    return component("autocomplete", params(p));
  }

  /**
   * autocompleteOptions component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content autocompleteOptions(Props.AutocompleteOptions p) {
    return component("autocomplete_options", params(p));
  }

  /**
   * pickList component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content pickList(Props.PickList p) {
    return component("pick_list", params(p));
  }

  /**
   * multiSelect component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content multiSelect(Props.MultiSelect p) {
    return component("multi_select", params(p));
  }

  /**
   * multiSelectSummary component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content multiSelectSummary(Props.MultiSelect p) {
    return component("multi_select_summary", params(p));
  }

  /**
   * multiSelectOptions component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content multiSelectOptions(Props.MultiSelect p) {
    return component("multi_select_options", params(p));
  }

  /**
   * codeChips component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content codeChips(Props.CodeChips p) {
    return component("code_chips", params(p));
  }

  /**
   * codeBlock component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content codeBlock(Props.CodeBlock p, @Nullable Content body) {
    return component("code_block", params(p, "body", body));
  }

  /**
   * search-select.md: a single choice from a searchable list of links.
   *
   * @param p props
   * @return the component
   */
  public static Content searchSelect(Props.SearchSelect p) {
    return component("search_select", params(p));
  }

  /**
   * code-editor.md: a textarea the {@code <fc-code-editor>} island replaces with CodeMirror. The
   * page must list {@code fc-code-editor.js} ({@code Views.page} with the script).
   *
   * @param p props
   * @return the component
   */
  public static Content codeEditor(Props.CodeEditor p) {
    return component("code_editor", params(p));
  }

  /**
   * diagram.md: Mermaid source drawn by the {@code <fc-diagram>} island. The page must list {@code
   * fc-diagram.js}.
   *
   * @param p props
   * @param source the Mermaid source as a slot; use {@link Markup#text} so it is escaped
   * @return the component
   */
  public static Content diagram(Props.Diagram p, @Nullable Content source) {
    return component("diagram", params(p, "source", source));
  }

  /**
   * markdown.md: Markdown rendered and sanitised on the server ({@link MarkdownHtml}). A render
   * failure shows the source as text.
   *
   * @param p props
   * @return the component
   */
  public static Content markdown(Props.Markdown p) {
    String source = p.source() == null ? "" : p.source();
    String html;
    try {
      html = MarkdownHtml.html(source);
    } catch (RuntimeException e) {
      html = "<pre>" + Markup.escape(source) + "</pre>";
    }
    Map<String, Object> m = params(p);
    m.put("html", html);
    return component("markdown", m);
  }

  /**
   * password-input.md: a password field with a show/hide toggle and an optional advisory meter.
   *
   * @param p props
   * @return the component
   */
  public static Content passwordInput(Props.PasswordInput p) {
    return component("password_input", params(p));
  }

  /**
   * secret-ref-input.md: a text field for the name of a secret, never its value.
   *
   * @param p props
   * @return the component
   */
  public static Content secretRefInput(Props.SecretRefInput p) {
    return component("secret_ref_input", params(p));
  }

  /**
   * permission-denied-dialog.md: the modal for a 403 or an expired session.
   *
   * @param p props
   * @return the component
   * @throws IllegalArgumentException (while rendering) without the URL the case needs
   */
  public static Content permissionDeniedDialog(Props.PermissionDeniedDialog p) {
    return component("permission_denied_dialog", params(p));
  }

  /**
   * tab-list.md: the ARIA tab pattern; the panels are the slots, by tab value.
   *
   * @param p props
   * @param panels each tab's content by value
   * @return the component
   */
  public static Content tabList(Props.TabList p, Map<String, Content> panels) {
    return component("tab_list", params(p, "panels", panels));
  }

  /**
   * appShell component (see its spec).
   *
   * @param p props
   * @param body the body slot
   * @return the component
   */
  public static Content appShell(Props.AppShell p, @Nullable Content body) {
    return component("app_shell", params(p, "body", body));
  }

  /**
   * appSidebar component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content appSidebar(Props.AppShell p) {
    return component("app_sidebar", params(p));
  }

  /**
   * userMenu component (see its spec).
   *
   * @param p props
   * @return the component
   */
  public static Content userMenu(Props.AppShell p) {
    return component("user_menu", params(p));
  }

  /**
   * detail-field.md: a read-only labelled value. Without a value the slot is the value; with
   * neither it is a dash.
   *
   * @param p props
   * @param body markup for the value, used when the value is empty
   * @return the component
   */
  public static Content detailField(Props.DetailField p, @Nullable Content body) {
    return output -> {
      String slot = body == null ? null : Markup.render(body);
      if (slot != null && slot.isBlank()) {
        slot = null;
      }
      ENGINE.render("kit/detail_field.jte", params(p, "slot", slot), output);
    };
  }

  /**
   * form-dialog.md's form-level error region: always present so a failed submit patches it by id.
   *
   * @param dialogId the dialog id
   * @param message the error, or null for an empty region
   * @return the component
   */
  public static Content formDialogError(String dialogId, @Nullable String message) {
    Map<String, Object> m = new HashMap<>();
    m.put("dialogId", dialogId);
    m.put("message", message);
    return component("form_dialog_error", m);
  }

  /**
   * The theme picker of the user menu.
   *
   * @param current system, light or dark
   * @return the component
   */
  public static Content themeChoices(String current) {
    return component("theme_choices", Map.of("current", current));
  }

  /**
   * The full HTML document around a page: head with the stylesheet and Datastar, the body.
   *
   * @param p props
   * @param body everything inside the body element
   * @return the component
   */
  public static Content document(Props.Document p, Content body) {
    return component("document", params(p, "body", body));
  }

  // ---- the thirteen components that were spec-only (GU4)

  /**
   * accordion component.
   *
   * @param p props
   * @param panels the content of each item, by its value
   * @return the component
   */
  public static Content accordion(Props.Accordion p, Map<String, Content> panels) {
    return component("accordion", params(p, "panels", panels));
  }

  /**
   * alert-dialog component.
   *
   * @param p props
   * @return the component
   */
  public static Content alertDialog(Props.AlertDialog p) {
    return component("alert_dialog", params(p));
  }

  /**
   * avatar component.
   *
   * @param p props
   * @return the component
   */
  public static Content avatar(Props.Avatar p) {
    return component("avatar", params(p));
  }

  /**
   * card component; the content and footer regions render only when their slot is not empty.
   *
   * @param p props
   * @param body the content slot
   * @param footer the footer slot
   * @return the component
   */
  public static Content card(Props.Card p, @Nullable Content body, @Nullable Content footer) {
    return component("card", params(p, "body", nonEmpty(body), "footer", nonEmpty(footer)));
  }

  /** The content rendered once, or null when it writes nothing. */
  private static @Nullable Content nonEmpty(@Nullable Content c) {
    if (c == null) {
      return null;
    }
    String html = Markup.render(c);
    return html.isEmpty() ? null : Markup.raw(html);
  }

  /**
   * dirty-form component.
   *
   * @param p props
   * @param body the fields, placed before the button row
   * @return the component
   */
  public static Content dirtyForm(Props.DirtyForm p, @Nullable Content body) {
    return component("dirty_form", params(p, "body", body));
  }

  /**
   * dropdown-menu component.
   *
   * @param p props
   * @param trigger the content of the summary
   * @param items the content of the panel
   * @return the component
   */
  public static Content dropdownMenu(Props.DropdownMenu p, @Nullable Content trigger, @Nullable Content items) {
    return component("dropdown_menu", params(p, "trigger", trigger, "items", items));
  }

  /**
   * hover-card component.
   *
   * @param p props
   * @param trigger the trigger markup, which carries {@code aria-describedby} of the id
   * @param body the card content
   * @return the component
   */
  public static Content hoverCard(Props.HoverCard p, @Nullable Content trigger, @Nullable Content body) {
    return component("hover_card", params(p, "trigger", trigger, "body", body));
  }

  /**
   * json-block component.
   *
   * @param p props
   * @return the component
   */
  public static Content jsonBlock(Props.JsonBlock p) {
    return component("json_block", params(p));
  }

  /**
   * kbd component.
   *
   * @param p props
   * @return the component
   */
  public static Content kbd(Props.Kbd p) {
    return component("kbd", params(p));
  }

  /**
   * progress component.
   *
   * @param p props
   * @return the component
   */
  public static Content progress(Props.Progress p) {
    return component("progress", params(p));
  }

  /**
   * radio-group component.
   *
   * @param p props
   * @return the component
   */
  public static Content radioGroup(Props.RadioGroup p) {
    return component("radio_group", params(p));
  }

  /**
   * separator component.
   *
   * @param p props
   * @return the component
   */
  public static Content separator(Props.Separator p) {
    return component("separator", params(p));
  }

  /**
   * tooltip component.
   *
   * @param p props
   * @param trigger the trigger markup, which carries {@code aria-describedby} of the id
   * @return the component
   */
  public static Content tooltip(Props.Tooltip p, @Nullable Content trigger) {
    return component("tooltip", params(p, "trigger", trigger));
  }
}
