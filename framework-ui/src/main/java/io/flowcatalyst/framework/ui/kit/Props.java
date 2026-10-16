package io.flowcatalyst.framework.ui.kit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The props of the kit's components, one record per component, named as the specs' Props tables
 * ({@code class} is {@code cls}, a Java keyword). A null (or false) is "absent": the spec's default
 * applies. {@code attrs} are extra attributes for the part the spec names ({@link Markup#attrs}).
 */
public final class Props {

  private Props() {}

  /** A (value, label) pair. */
  public record Option(String value, String label) {}

  // ---- display

  /**
   * badge.md.
   *
   * @param variant primary, secondary, outline, destructive, success, info, warn
   * @param href renders a link
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record Badge(
      @Nullable String variant,
      @Nullable String href,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * tag.md.
   *
   * @param severity success, info, warn, danger, secondary, primary
   * @param label the text
   */
  public record Tag(@Nullable String severity, @Nullable String label) {}

  /**
   * alert.md.
   *
   * @param variant neutral, destructive, warn
   * @param role alert or status, only for messages inserted during the visit
   * @param icon leading icon
   * @param title heading
   * @param description text
   * @param id element id
   * @param cls extra classes
   */
  public record Alert(
      @Nullable String variant,
      @Nullable String role,
      @Nullable String icon,
      @Nullable String title,
      @Nullable String description,
      @Nullable String id,
      @Nullable String cls) {}

  /**
   * flash-banner.md.
   *
   * @param kind success or error
   * @param message the text
   * @param role defaults to status
   * @param autoDismiss removes the banner after five seconds
   */
  public record FlashBanner(
      @Nullable String kind, @Nullable String message, @Nullable String role, boolean autoDismiss) {}

  /**
   * flash-stack.md, flash message.
   *
   * @param kind success, info, warn, error
   * @param summary the headline
   * @param detail the second line
   * @param persistent keeps a success or info message
   * @param attrs extra attributes
   */
  public record FlashMessage(
      @Nullable String kind,
      @Nullable String summary,
      @Nullable String detail,
      boolean persistent,
      @Nullable Map<String, Object> attrs) {}

  /**
   * flash-stack.md, the live region.
   *
   * @param id the element id patches append to
   */
  public record FlashStack(String id) {}

  /**
   * spinner.md.
   *
   * @param size size class, default size-6
   * @param label accessible name, default Loading
   * @param decorative aria-hidden instead of labelled
   */
  public record Spinner(@Nullable String size, @Nullable String label, boolean decorative) {}

  /**
   * skeleton.md.
   *
   * @param cls size and shape classes
   */
  public record Skeleton(@Nullable String cls) {}

  /**
   * One breadcrumb item.
   *
   * @param label the text
   * @param href the link; the last item (or one without) is the current page
   */
  public record Crumb(String label, @Nullable String href) {}

  /**
   * breadcrumb.md.
   *
   * @param items the trail
   */
  public record Breadcrumb(List<Crumb> items) {}

  /**
   * empty-state.md.
   *
   * @param message the text
   * @param icon default inbox
   * @param clearHref renders a Clear filters link
   * @param status adds role status
   */
  public record EmptyState(
      @Nullable String message, @Nullable String icon, @Nullable String clearHref, boolean status) {}

  /**
   * page-header.md; the actions are a slot.
   *
   * @param title the heading
   * @param subtitle the line under it
   */
  public record PageHeader(@Nullable String title, @Nullable String subtitle) {}

  // ---- buttons

  /**
   * button.md.
   *
   * @param variant primary, secondary, outline, ghost, destructive, text, destructive-outline,
   *     success-outline
   * @param size sm, md, lg, icon
   * @param type the button type, default button
   * @param href renders a link with the same classes
   * @param disabled disabled
   * @param icon leading icon
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record Button(
      @Nullable String variant,
      @Nullable String size,
      @Nullable String type,
      @Nullable String href,
      boolean disabled,
      @Nullable String icon,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * legacy-fc-btn.md.
   *
   * @param variant primary, primary-outline, secondary, outline, warn-outline, danger-outline,
   *     danger, warn, text, text-primary, link
   * @param small small
   * @param block full width
   * @param type the button type, default button
   * @param href renders a link
   * @param disabled disabled
   * @param icon leading icon
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record LegacyButton(
      @Nullable String variant,
      boolean small,
      boolean block,
      @Nullable String type,
      @Nullable String href,
      boolean disabled,
      @Nullable String icon,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * The round ghost icon button.
   *
   * @param icon the icon
   * @param label the accessible name (required)
   * @param type the button type, default button
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record IconButton(
      String icon,
      String label,
      @Nullable String type,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  // ---- forms

  /**
   * input.md.
   *
   * @param id the id
   * @param name the name
   * @param type default text
   * @param value the value
   * @param placeholder the placeholder
   * @param required required
   * @param disabled disabled
   * @param invalid sets aria-invalid
   * @param describedBy the id of the error or help text
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record Input(
      @Nullable String id,
      @Nullable String name,
      @Nullable String type,
      @Nullable String value,
      @Nullable String placeholder,
      boolean required,
      boolean disabled,
      boolean invalid,
      @Nullable String describedBy,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * textarea.md.
   *
   * @param id the id
   * @param name the name
   * @param value the text
   * @param placeholder the placeholder
   * @param rows rows, 0 for the default
   * @param required required
   * @param disabled disabled
   * @param invalid sets aria-invalid
   * @param describedBy the id of the error or help text
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record Textarea(
      @Nullable String id,
      @Nullable String name,
      @Nullable String value,
      @Nullable String placeholder,
      int rows,
      boolean required,
      boolean disabled,
      boolean invalid,
      @Nullable String describedBy,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * select.md.
   *
   * @param id the id
   * @param name the name
   * @param options the options
   * @param selected the selected value
   * @param placeholder adds a first option with an empty value
   * @param required required
   * @param disabled disabled
   * @param invalid sets aria-invalid
   * @param describedBy the id of the error or help text
   * @param cls classes for the wrapper
   * @param attrs attributes for the select
   */
  public record Select(
      @Nullable String id,
      @Nullable String name,
      @Nullable List<Option> options,
      @Nullable String selected,
      @Nullable String placeholder,
      boolean required,
      boolean disabled,
      boolean invalid,
      @Nullable String describedBy,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * checkbox.md.
   *
   * @param id the id
   * @param name the name
   * @param value the value
   * @param checked checked
   * @param disabled disabled
   * @param label the sibling label text
   * @param cls classes for the wrapper
   * @param attrs attributes for the input
   */
  public record Checkbox(
      @Nullable String id,
      @Nullable String name,
      @Nullable String value,
      boolean checked,
      boolean disabled,
      @Nullable String label,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * switch.md.
   *
   * @param id the id
   * @param name the name
   * @param value the value
   * @param checked checked
   * @param disabled disabled
   * @param label the sibling label text
   * @param cls classes for the wrapper
   * @param attrs attributes for the input
   */
  public record Switch(
      @Nullable String id,
      @Nullable String name,
      @Nullable String value,
      boolean checked,
      boolean disabled,
      @Nullable String label,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * label.md.
   *
   * @param forId the control's id (the {@code for} prop)
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record Label(
      @Nullable String forId, @Nullable String cls, @Nullable Map<String, Object> attrs) {}

  /**
   * field.md.
   *
   * @param label the label text
   * @param forId the control's id (the {@code for} prop)
   * @param orientation vertical, horizontal, responsive
   * @param description help text
   * @param error the error text
   * @param errorId the error element id, default forId + -err
   * @param required shows the marker
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record Field(
      @Nullable String label,
      @Nullable String forId,
      @Nullable String orientation,
      @Nullable String description,
      @Nullable String error,
      @Nullable String errorId,
      boolean required,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * field.md, field group.
   *
   * @param cls extra classes
   */
  public record FieldGroup(@Nullable String cls) {}

  /**
   * form-field.md.
   *
   * @param label the label text
   * @param forId the control's id (the {@code for} prop)
   * @param required shows the marker
   * @param span spans both columns of the form grid
   * @param help help text
   * @param error replaces help
   */
  public record FormField(
      @Nullable String label,
      @Nullable String forId,
      boolean required,
      boolean span,
      @Nullable String help,
      @Nullable String error) {}

  /**
   * search-input.md.
   *
   * @param name default q
   * @param placeholder the placeholder
   * @param value the value
   * @param cls replaces the wrapper's width classes
   * @param attrs attributes for the input
   */
  public record SearchInput(
      @Nullable String name,
      @Nullable String placeholder,
      @Nullable String value,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * filter-select.md.
   *
   * @param name the name
   * @param label the label text
   * @param placeholder the empty option
   * @param options the options
   * @param selected the selected value
   * @param autoSubmit submits the form on change
   * @param attrs attributes for the select
   */
  public record FilterSelect(
      @Nullable String name,
      @Nullable String label,
      @Nullable String placeholder,
      @Nullable List<Option> options,
      @Nullable String selected,
      boolean autoSubmit,
      @Nullable Map<String, Object> attrs) {}

  /**
   * toggle.md, group.
   *
   * @param kind independent or exclusive
   * @param size sm, md, lg
   * @param name the input name
   * @param options the options
   * @param selected the selected values
   * @param attrs attributes for the group
   * @param inputAttrs attributes for each input
   */
  public record ToggleGroup(
      @Nullable String kind,
      @Nullable String size,
      @Nullable String name,
      @Nullable List<Option> options,
      @Nullable List<String> selected,
      @Nullable Map<String, Object> attrs,
      @Nullable Map<String, Object> inputAttrs) {}

  // ---- tables and lists

  /**
   * table.md: the scrolling container and the table.
   *
   * @param id the container id
   * @param caption the caption
   * @param dense the list-page cell padding
   * @param cls extra classes for the table
   * @param attrs extra attributes for the table
   */
  public record Table(
      @Nullable String id,
      @Nullable String caption,
      boolean dense,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * table.md, row.
   *
   * @param isStatic header row, no hover tint (the {@code static} prop)
   * @param clickable adds the pointer cursor
   * @param id the row id
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record TR(
      boolean isStatic,
      boolean clickable,
      @Nullable String id,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {}

  /**
   * table.md, head cell.
   *
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record TH(@Nullable String cls, @Nullable Map<String, Object> attrs) {}

  /**
   * table.md, cell.
   *
   * @param colspan column span, 0 for none
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record TD(int colspan, @Nullable String cls, @Nullable Map<String, Object> attrs) {}

  /**
   * data-table.md, server-sorted heading.
   *
   * @param label the text
   * @param href the URL that sorts by this column
   * @param direction asc or desc when this column is the sort, else null
   * @param cls extra classes
   * @param linkAttrs attributes for the link
   */
  public record SortableHeader(
      @Nullable String label,
      @Nullable String href,
      @Nullable String direction,
      @Nullable String cls,
      @Nullable Map<String, Object> linkAttrs) {}

  /**
   * table.md, the row shown instead of rows.
   *
   * @param colspan the column count
   * @param empty the empty state inside
   */
  public record EmptyRow(int colspan, EmptyState empty) {}

  /**
   * One action of a row (row-actions.md).
   *
   * @param icon the icon
   * @param label the visible menu text and the inline tooltip
   * @param href makes the action a link
   * @param attrs the handler of a button action
   * @param destructive red; asks for confirmation
   * @param disabledReason renders the action disabled with this reason
   */
  public record RowAction(
      @Nullable String icon,
      @Nullable String label,
      @Nullable String href,
      @Nullable Map<String, Object> attrs,
      boolean destructive,
      @Nullable String disabledReason) {}

  /**
   * row-actions.md.
   *
   * @param id prefix of the generated ids; required with a menu or a disabled action
   * @param rowLabel the row's name for accessible names
   * @param actions the inline actions
   * @param menu the overflow menu
   * @param attrs attributes for the cell
   */
  public record RowActions(
      @Nullable String id,
      @Nullable String rowLabel,
      @Nullable List<RowAction> actions,
      @Nullable List<RowAction> menu,
      @Nullable Map<String, Object> attrs) {

    /**
     * An accessible name with the row appended.
     *
     * @param label the action
     * @return the name
     */
    public String withRow(@Nullable String label) {
      String l = label == null ? "" : label;
      return Markup.nz(rowLabel) == null ? l : l + " " + rowLabel;
    }

    /**
     * The overflow button's label.
     *
     * @return the label
     */
    public String moreLabel() {
      return Markup.nz(rowLabel) == null ? "More actions" : "More actions for " + rowLabel;
    }

    /**
     * A generated id.
     *
     * @param kind a or m
     * @param i the index
     * @return the id
     */
    public String partId(String kind, int i) {
      return Markup.id(id) + "-" + kind + i;
    }

    /**
     * The menu's id.
     *
     * @return the id
     */
    public String menuId() {
      return Markup.id(id) + "-menu";
    }

    /**
     * Whether menu item i is the first destructive one with a plain one before it.
     *
     * @param i the index
     * @return true when a separator goes before it
     */
    public boolean separatorBefore(int i) {
      List<RowAction> m = menu == null ? List.of() : menu;
      if (!m.get(i).destructive()) {
        return false;
      }
      boolean plain = false;
      for (RowAction a : m.subList(0, i)) {
        if (a.destructive()) {
          return false;
        }
        plain = true;
      }
      return plain;
    }
  }

  /**
   * One column of a data table.
   *
   * @param label the heading
   * @param href makes the column sortable (the URL that sorts by it)
   * @param direction asc or desc when it is the active sort
   * @param cls extra classes
   * @param linkAttrs attributes for the sort link
   */
  public record DataColumn(
      @Nullable String label,
      @Nullable String href,
      @Nullable String direction,
      @Nullable String cls,
      @Nullable Map<String, Object> linkAttrs) {}

  /**
   * data-table.md: the list-page table.
   *
   * @param id the container id
   * @param bodyId the tbody id, the rows' patch target
   * @param columns the columns
   * @param empty when set, replaces the rows with an empty row
   */
  public record DataTable(
      @Nullable String id,
      @Nullable String bodyId,
      @Nullable List<DataColumn> columns,
      @Nullable EmptyState empty) {}

  /**
   * A hidden input carried by a form.
   *
   * @param name the name
   * @param value the value
   * @param attrs extra attributes
   */
  public record HiddenField(
      @Nullable String name, @Nullable String value, @Nullable Map<String, Object> attrs) {}

  /**
   * The toolbar's result-size select.
   *
   * @param options the sizes
   * @param selected the chosen size
   */
  public record ResultSize(@Nullable List<Integer> options, int selected) {}

  /**
   * table-toolbar.md.
   *
   * @param id the form id
   * @param action the form action
   * @param searchPlaceholder the search placeholder
   * @param searchValue the search value
   * @param hideSearch omits the search box
   * @param size the result-size select
   * @param hideRefresh omits the refresh button
   * @param clearHref renders Clear All
   * @param clearAttrs attributes for Clear All
   * @param activeFilterCount the badge count
   * @param hidden hidden inputs
   * @param searchAttrs attributes for the search input
   * @param formAttrs attributes for the form
   */
  public record TableToolbar(
      String id,
      @Nullable String action,
      @Nullable String searchPlaceholder,
      @Nullable String searchValue,
      boolean hideSearch,
      @Nullable ResultSize size,
      boolean hideRefresh,
      @Nullable String clearHref,
      @Nullable Map<String, Object> clearAttrs,
      int activeFilterCount,
      @Nullable List<HiddenField> hidden,
      @Nullable Map<String, Object> searchAttrs,
      @Nullable Map<String, Object> formAttrs) {

    /**
     * The state part of this toolbar: render it alone (and patch it by id) after a live update.
     *
     * @param hasFilters whether the toolbar has a filters popover (the Filters button)
     * @return the state props
     */
    public TableToolbarState state(boolean hasFilters) {
      return new TableToolbarState(
          id, hidden, clearHref, clearAttrs, activeFilterCount, hasFilters);
    }
  }

  /**
   * table-toolbar.md, the state part.
   *
   * @param formId the toolbar form's id
   * @param hidden hidden inputs
   * @param clearHref renders Clear All
   * @param clearAttrs attributes for Clear All
   * @param activeFilterCount the badge count
   * @param filters renders the Filters button
   */
  public record TableToolbarState(
      String formId,
      @Nullable List<HiddenField> hidden,
      @Nullable String clearHref,
      @Nullable Map<String, Object> clearAttrs,
      int activeFilterCount,
      boolean filters) {}

  /**
   * One entry of a pagination.
   *
   * @param page the page number
   * @param href the link
   * @param ellipsis an ellipsis instead of a page
   */
  public record PageItem(int page, @Nullable String href, boolean ellipsis) {}

  /**
   * pagination.md.
   *
   * @param items the entries
   * @param current the current page
   * @param prevHref the previous link, null for the disabled form
   * @param nextHref the next link, null for the disabled form
   * @param primaryCurrent fills the current page with the primary colour
   * @param linkAttrs attributes for every page link
   */
  public record Pagination(
      @Nullable List<PageItem> items,
      int current,
      @Nullable String prevHref,
      @Nullable String nextHref,
      boolean primaryCurrent,
      @Nullable Map<String, Object> linkAttrs) {}

  /**
   * cursor-pager.md.
   *
   * @param id the footer id
   * @param summary the summary text
   * @param newestHref the Newest link, null for the disabled form
   * @param olderHref the Older link, null for the disabled form
   * @param linkAttrs attributes for the links
   */
  public record CursorPager(
      @Nullable String id,
      @Nullable String summary,
      @Nullable String newestHref,
      @Nullable String olderHref,
      @Nullable Map<String, Object> linkAttrs) {}

  /**
   * paginator.md.
   *
   * @param id the nav id
   * @param page the current page
   * @param pages the page count
   * @param total the row count
   * @param rows the rows per page
   * @param rowsOptions the choices, default 50, 100, 250, 500
   * @param noun the noun in the report
   * @param hrefPattern a page link with the placeholder {@code {page}}
   * @param formId the list's form, for the rows select; empty for none
   * @param linkAttrs attributes for the page links
   */
  public record Paginator(
      @Nullable String id,
      int page,
      int pages,
      int total,
      int rows,
      @Nullable List<Integer> rowsOptions,
      @Nullable String noun,
      @Nullable String hrefPattern,
      @Nullable String formId,
      @Nullable Map<String, Object> linkAttrs) {

    private static final List<Integer> DEFAULT_ROWS = List.of(50, 100, 250, 500);

    /**
     * The page count, at least one.
     *
     * @return the pages
     */
    public int pageCount() {
      return Math.max(pages, 1);
    }

    /**
     * The current page clamped to the range.
     *
     * @return the page
     */
    public int currentPage() {
      return Math.min(Math.max(page, 1), pageCount());
    }

    /**
     * The rows per page.
     *
     * @return rows, 50 when unset
     */
    public int rowCount() {
      return rows <= 0 ? DEFAULT_ROWS.get(0) : rows;
    }

    /**
     * The rows-per-page choices.
     *
     * @return the options
     */
    public List<Integer> rowChoices() {
      return rowsOptions == null || rowsOptions.isEmpty() ? DEFAULT_ROWS : rowsOptions;
    }

    /**
     * A page link.
     *
     * @param n the page
     * @return the pattern with the page filled in
     */
    public String href(int n) {
      return (hrefPattern == null ? "" : hrefPattern).replace("{page}", Integer.toString(n));
    }

    /**
     * The five page numbers around the current page.
     *
     * @return the window
     */
    public List<Integer> window() {
      int pg = pageCount();
      int cur = currentPage();
      int start = Math.max(1, Math.min(Math.max(cur - 2, 1), Math.max(pg - 4, 1)));
      int end = Math.min(start + 4, pg);
      List<Integer> out = new java.util.ArrayList<>();
      for (int n = start; n <= end; n++) {
        out.add(n);
      }
      return out;
    }

    /**
     * The "Showing a to b of c" text.
     *
     * @return the report
     */
    public String report() {
      int first = 0;
      int last = 0;
      if (total > 0) {
        first = (currentPage() - 1) * rowCount() + 1;
        last = Math.min(currentPage() * rowCount(), total);
      }
      return "Showing " + first + " to " + last + " of " + total + " " + (noun == null ? "" : noun);
    }
  }

  /**
   * One trigger of tabs.
   *
   * @param value an id fragment, part of the panel id and of the trigger's expression
   * @param label the text
   */
  public record TabItem(String value, String label) {}

  /**
   * tabs.md: client tabs, one signal holds the selected value. The panels are slots by value.
   *
   * @param signal the signal name, camelCase
   * @param tabs the tabs
   * @param selected the initial tab, default the first
   * @param listClass extra classes for the trigger row
   * @param cls extra classes for the root
   */
  public record Tabs(
      String signal,
      List<TabItem> tabs,
      @Nullable String selected,
      @Nullable String listClass,
      @Nullable String cls) {

    /**
     * The initially selected value.
     *
     * @return the value, empty when there are no tabs
     */
    public String initial() {
      if (Markup.nz(selected) != null) {
        return Markup.id(selected);
      }
      return tabs.isEmpty() ? "" : Markup.id(tabs.get(0).value());
    }

    /**
     * A panel's id.
     *
     * @param value the tab value
     * @return the id
     */
    public String panelId(String value) {
      return Markup.signal(signal) + "-" + Markup.id(value);
    }

    /**
     * The expression that is true while the tab is selected.
     *
     * @param value the tab value
     * @return the expression
     */
    public String is(String value) {
      return "$" + Markup.signal(signal) + " === '" + Markup.id(value) + "'";
    }

    /**
     * The expression that selects the tab.
     *
     * @param value the tab value
     * @return the expression
     */
    public String select(String value) {
      return "$" + Markup.signal(signal) + " = '" + Markup.id(value) + "'";
    }
  }

  /**
   * local-time.md.
   *
   * @param at the instant; null renders the empty text
   * @param dateOnly the date without the time
   * @param empty the text for a null instant, default a dash
   */
  public record LocalTime(
      @Nullable Instant at, boolean dateOnly, @Nullable String empty) {}

  /**
   * date-range.md.
   *
   * @param id derives the parts: -label, -from, -to
   * @param label the group label
   * @param fromName default from
   * @param toName default to
   * @param from the start date
   * @param to the end date
   * @param fromLabel default From
   * @param toLabel default To
   * @param attrs attributes for both inputs
   */
  public record DateRange(
      String id,
      @Nullable String label,
      @Nullable String fromName,
      @Nullable String toName,
      @Nullable String from,
      @Nullable String to,
      @Nullable String fromLabel,
      @Nullable String toLabel,
      @Nullable Map<String, Object> attrs) {}
}
