package io.flowcatalyst.framework.ui.kit;

import gg.jte.Content;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/** Fixture directory name to the kit function that renders it (the Go test's {@code renderers}). */
final class Renderers {

  /** The slots a fixture fills; asking for one marks it used so unused slots can be reported. */
  static final class Slots {
    private final Map<String, String> html;
    final Set<String> used = new HashSet<>();

    Slots(Map<String, String> html) {
      this.html = html;
    }

    @Nullable Content get(String name) {
      String s = html.get(name);
      if (s == null) {
        return null;
      }
      used.add(name);
      return Markup.raw(s);
    }
  }

  record Renderer(Class<?> props, BiFunction<Object, Slots, Content> fn) {}

  static final Map<String, Renderer> ALL = new LinkedHashMap<>();

  @SuppressWarnings("unchecked")
  private static <P> void r(String name, Class<P> props, BiFunction<P, Slots, Content> fn) {
    ALL.put(name, new Renderer(props, (p, s) -> fn.apply((P) p, s)));
  }

  static {
    r("badge", Props.Badge.class, (p, s) -> Kit.badge(p, nn(s.get("default"))));
    r("tag", Props.Tag.class, (p, s) -> Kit.tag(p));
    r("alert", Props.Alert.class, (p, s) -> Kit.alert(p, s.get("default")));
    r("flash-banner", Props.FlashBanner.class, (p, s) -> Kit.flashBanner(p));
    r("flash-message", Props.FlashMessage.class, (p, s) -> Kit.flashMessage(p));
    r("flash-stack", Props.FlashStack.class, (p, s) -> Kit.flashStack(p, s.get("default")));
    r("spinner", Props.Spinner.class, (p, s) -> Kit.spinner(p));
    r("skeleton", Props.Skeleton.class, (p, s) -> Kit.skeleton(p));
    r("breadcrumb", Props.Breadcrumb.class, (p, s) -> Kit.breadcrumb(p));
    r("empty-state", Props.EmptyState.class, (p, s) -> Kit.emptyState(p));
    r("page-header", Props.PageHeader.class, (p, s) -> Kit.pageHeader(p, s.get("actions")));
    r("button", Props.Button.class, (p, s) -> Kit.button(p, s.get("default")));
    r("legacy-button", Props.LegacyButton.class, (p, s) -> Kit.legacyButton(p, s.get("default")));
    r("icon-button", Props.IconButton.class, (p, s) -> Kit.iconButton(p));
    r("input", Props.Input.class, (p, s) -> Kit.input(p));
    r("textarea", Props.Textarea.class, (p, s) -> Kit.textarea(p));
    r("select", Props.Select.class, (p, s) -> Kit.select(p));
    r("checkbox", Props.Checkbox.class, (p, s) -> Kit.checkbox(p));
    r("switch", Props.Switch.class, (p, s) -> Kit.switchControl(p));
    r("label", Props.Label.class, (p, s) -> Kit.label(p, s.get("default")));
    r("field", Props.Field.class, (p, s) -> Kit.field(p, s.get("default")));
    r("field-group", Props.FieldGroup.class, (p, s) -> Kit.fieldGroup(p, s.get("default")));
    r("form-field", Props.FormField.class, (p, s) -> Kit.formField(p, s.get("default")));
    r("search-input", Props.SearchInput.class, (p, s) -> Kit.searchInput(p));
    r("filter-select", Props.FilterSelect.class, (p, s) -> Kit.filterSelect(p));
    r("toggle-group", Props.ToggleGroup.class, (p, s) -> Kit.toggleGroup(p));
    r("table", Props.Table.class, (p, s) -> Kit.table(p, s.get("default")));
    r("table-row", Props.TR.class, (p, s) -> Kit.tableRow(p, s.get("default")));
    r("table-head", Props.TH.class, (p, s) -> Kit.tableHead(p, s.get("default")));
    r("table-cell", Props.TD.class, (p, s) -> Kit.tableCell(p, s.get("default")));
    r("sortable-header", Props.SortableHeader.class, (p, s) -> Kit.sortableHeader(p));
    r("empty-row", Props.EmptyRow.class, (p, s) -> Kit.emptyRow(p));
    r("data-table", Props.DataTable.class, (p, s) -> Kit.dataTable(p, s.get("default")));
    r("row-actions", Props.RowActions.class, (p, s) -> Kit.rowActions(p));
    r("table-toolbar", Props.TableToolbar.class, (p, s) -> Kit.tableToolbar(p, s.get("filters")));
    r("table-toolbar-state", Props.TableToolbarState.class, (p, s) -> Kit.tableToolbarState(p));
    r("pagination", Props.Pagination.class, (p, s) -> Kit.pagination(p));
    r("cursor-pager", Props.CursorPager.class, (p, s) -> Kit.cursorPager(p));
    r("paginator", Props.Paginator.class, (p, s) -> Kit.paginator(p));
    r("local-time", Props.LocalTime.class, (p, s) -> Kit.localTime(p));
    r("date-range", Props.DateRange.class, (p, s) -> Kit.dateRange(p));
    r(
        "tabs",
        Props.Tabs.class,
        (p, s) -> {
          Map<String, Content> panels = new HashMap<>();
          for (Props.TabItem t : p.tabs()) {
            Content c = s.get(t.value());
            if (c != null) {
              panels.put(t.value(), c);
            }
          }
          return Kit.tabs(p, panels);
        });
  }

  private static Content nn(@Nullable Content c) {
    if (c == null) {
      throw new IllegalArgumentException("the fixture must fill the default slot");
    }
    return c;
  }

  private Renderers() {}

  static Map<String, String> noSlots() {
    return new HashMap<>();
  }
}
