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

  // ---- overlays and dialogs

  /**
   * dialog.md: a full-viewport modal opened with {@code showModal()} from a Datastar handler
   * ({@link Markup#openModal}). The body is the default slot, the actions the {@code footer} slot.
   *
   * @param id the dialog id (required: the trigger and the aria ids use it)
   * @param title the heading
   * @param description the text under it
   * @param mask frosted (default) or dark
   * @param width default, narrow or wide
   * @param persistent turns light dismiss (mask click closes) off
   * @param closable renders the close button
   * @param role alertdialog for an alert dialog
   * @param attrs extra attributes for the dialog
   */
  public record Dialog(
      String id,
      @Nullable String title,
      @Nullable String description,
      @Nullable String mask,
      @Nullable String width,
      boolean persistent,
      boolean closable,
      @Nullable String role,
      @Nullable Map<String, Object> attrs) {}

  /**
   * confirm-dialog.md.
   *
   * @param id the dialog id
   * @param action the form's POST target
   * @param title the heading
   * @param message the question
   * @param confirmLabel the submit button's text
   * @param tone default, danger or warn
   * @param hidden fields posted with the form
   * @param formAttrs attributes for the form
   */
  public record ConfirmDialog(
      String id,
      @Nullable String action,
      @Nullable String title,
      @Nullable String message,
      @Nullable String confirmLabel,
      @Nullable String tone,
      @Nullable List<HiddenField> hidden,
      @Nullable Map<String, Object> formAttrs) {}

  /**
   * sheet.md: a modal with an edge panel.
   *
   * @param id the dialog id
   * @param side right (default), left, top, bottom
   * @param title the heading
   * @param description the text under it
   * @param cls extra classes for the panel
   */
  public record Sheet(
      String id,
      @Nullable String side,
      @Nullable String title,
      @Nullable String description,
      @Nullable String cls) {}

  /**
   * drawer.md: the non-modal record drawer over a list (also the props of its content part).
   *
   * @param id the frame id; makes the title focusable
   * @param signal holds the selected record id, empty is closed
   * @param label the aria label
   * @param size default, wide, two-thirds
   * @param title static title text
   * @param subtitle static subtitle text
   * @param titleSignal binds the title to a signal instead
   * @param subtitleSignal binds the subtitle to a signal instead
   * @param bodyId the body's patch target
   */
  public record Drawer(
      @Nullable String id,
      String signal,
      @Nullable String label,
      @Nullable String size,
      @Nullable String title,
      @Nullable String subtitle,
      @Nullable String titleSignal,
      @Nullable String subtitleSignal,
      @Nullable String bodyId) {

    /**
     * The expression true while a record is open.
     *
     * @return the expression
     */
    public String showExpr() {
      return "$" + Markup.signal(signal) + " !== ''";
    }

    /**
     * Escape closes the drawer unless a dialog or popover is open above it.
     *
     * @return the expression
     */
    public String escapeExpr() {
      String s = "$" + Markup.signal(signal);
      return "evt.key === 'Escape' && "
          + s
          + " !== '' && !document.querySelector('dialog[open]:not([data-drawer]), [popover]:popover-open') && ("
          + s
          + " = '')";
    }

    /**
     * The close button's expression.
     *
     * @return the expression
     */
    public String closeExpr() {
      return "$" + Markup.signal(signal) + " = ''";
    }
  }

  /**
   * form-dialog.md: a create or edit form in a modal that posts through Datastar (and natively
   * without it). The fields are the default slot.
   *
   * @param id the dialog id; the parts derive from it
   * @param title the heading
   * @param description the text under it
   * @param width default, narrow or wide
   * @param action the form's POST target
   * @param submitLabel the submit button's text
   * @param signal prefixes the form's two signals ({@code <signal>Dirty}, {@code <signal>Busy})
   * @param requireChange keeps Submit disabled until a value differs
   * @param open renders the dialog open and makes it modal on insert
   * @param error the form-level error
   * @param hidden hidden inputs
   */
  public record FormDialog(
      String id,
      @Nullable String title,
      @Nullable String description,
      @Nullable String width,
      @Nullable String action,
      @Nullable String submitLabel,
      String signal,
      boolean requireChange,
      boolean open,
      @Nullable String error,
      @Nullable List<HiddenField> hidden) {

    /** The expression that snapshots the form's values. */
    public static final String SNAPSHOT = "new URLSearchParams(new FormData(el)).toString()";

    /**
     * The id of a form dialog's form-level error region.
     *
     * @param dialogId the dialog id
     * @return the id
     */
    public static String errorId(String dialogId) {
      return Markup.id(dialogId) + "-error";
    }

    /**
     * The id of a form dialog's form.
     *
     * @param dialogId the dialog id
     * @return the id
     */
    public static String formId(String dialogId) {
      return Markup.id(dialogId) + "-form";
    }

    /**
     * The dirty signal reference.
     *
     * @return the expression
     */
    public String dirty() {
      return "$" + Markup.signal(signal) + "Dirty";
    }

    /**
     * The busy signal reference.
     *
     * @return the expression
     */
    public String busy() {
      return "$" + Markup.signal(signal) + "Busy";
    }

    /**
     * Closes the dialog, or asks first when the form has changes.
     *
     * @return the expression
     */
    public String dismissExpr() {
      return dirty()
          + " ? document.getElementById('"
          + Markup.id(id)
          + "-discard').showModal() : document.getElementById('"
          + Markup.id(id)
          + "').close()";
    }

    /**
     * The dialog's own attributes.
     *
     * @return the attributes
     */
    public Map<String, Object> dialogAttrs() {
      Map<String, Object> a = new java.util.LinkedHashMap<>();
      a.put("data-on:click", "evt.target === el && (" + dismissExpr() + ")");
      a.put(
          "data-on:cancel",
          dirty()
              + " && (evt.preventDefault(), document.getElementById('"
              + Markup.id(id)
              + "-discard').showModal())");
      if (open) {
        a.put("open", true);
        // A dialog rendered open is non-modal; reopen it as a modal once Datastar processes it.
        a.put("data-init", "el.open && el.close(); el.showModal()");
      }
      return a;
    }

    /**
     * Declares the two signals (object form, so the attribute name is static).
     *
     * @return the expression
     */
    public String signalsExpr() {
      return "{" + Markup.signal(signal) + "Dirty: false, " + Markup.signal(signal) + "Busy: false}";
    }

    /**
     * The busy indicator's signal name.
     *
     * @return the name
     */
    public String busyName() {
      return Markup.signal(signal) + "Busy";
    }

    /**
     * Marks the form dirty when its values differ from the rendered ones.
     *
     * @return the expression
     */
    public String dirtyExpr() {
      return dirty() + " = " + SNAPSHOT + " !== el.dataset.snapshot";
    }

    /**
     * The aria-busy binding.
     *
     * @return the expression
     */
    public String ariaBusyExpr() {
      return busy() + " ? 'true' : 'false'";
    }

    /**
     * When the submit button is disabled.
     *
     * @return the expression
     */
    public String submitDisabledExpr() {
      return requireChange ? busy() + " || !" + dirty() : busy();
    }
  }

  /**
   * form-section.md: a titled block of a drawer or page.
   *
   * @param title the heading
   * @param description the text under it
   * @param danger colours the title red
   * @param card draws a bordered panel
   */
  public record FormSection(
      @Nullable String title, @Nullable String description, boolean danger, boolean card) {}

  /**
   * detail-field.md: a read-only labelled value.
   *
   * @param label the label
   * @param value escaped text; without it the default slot is the value, with neither a dash
   * @param span spans both columns of the detail grid
   */
  public record DetailField(@Nullable String label, @Nullable String value, boolean span) {}

  /**
   * autocomplete.md: an input with suggestions the server searches as the user types.
   *
   * @param id the input id
   * @param name the name
   * @param value the value
   * @param placeholder the placeholder
   * @param source the search endpoint, a local path without a query
   * @param options suggestions rendered with the page
   * @param required required
   * @param disabled disabled
   * @param invalid sets aria-invalid
   * @param describedBy the id of the error or help text
   * @param cls extra classes
   * @param attrs extra attributes
   */
  public record Autocomplete(
      String id,
      @Nullable String name,
      @Nullable String value,
      @Nullable String placeholder,
      @Nullable String source,
      @Nullable List<Option> options,
      boolean required,
      boolean disabled,
      boolean invalid,
      @Nullable String describedBy,
      @Nullable String cls,
      @Nullable Map<String, Object> attrs) {

    /**
     * The datalist id of the autocomplete with this id.
     *
     * @param id the input id
     * @return the datalist id
     */
    public static String listId(String id) {
      return Markup.id(id) + "-options";
    }

    /**
     * The debounced search expression.
     *
     * @return the expression
     */
    public String searchExpr() {
      return "@get('"
          + Markup.path(source)
          + "?list="
          + listId(id)
          + "&q=' + encodeURIComponent(el.value))";
    }
  }

  /**
   * autocomplete.md, the datalist part.
   *
   * @param id the datalist id
   * @param options the suggestions
   */
  public record AutocompleteOptions(String id, @Nullable List<Option> options) {}

  /**
   * One entry of a pick list.
   *
   * @param value submitted; id-safe, never enters an expression
   * @param name the text
   * @param code the code line
   * @param note a small caption
   * @param checked picked
   */
  public record PickItem(
      String value,
      @Nullable String name,
      @Nullable String code,
      @Nullable String note,
      boolean checked) {}

  /**
   * pick-list.md: the dual-pane picker that posts the picked values under one name.
   *
   * @param id the form id
   * @param signal the picked signal
   * @param filterSignal the filter text signal
   * @param name the submitted name
   * @param itemIdPrefix prefix of the checkbox ids
   * @param items the entries
   * @param availableTitle the left pane's heading
   * @param selectedTitle the right pane's heading, default Selected
   * @param filterPlaceholder the filter box's placeholder
   * @param emptyText the text when nothing is picked
   * @param saveLabel the save button's text
   * @param action the form's POST target
   * @param formAttrs extra attributes for the form
   */
  public record PickList(
      String id,
      String signal,
      String filterSignal,
      @Nullable String name,
      @Nullable String itemIdPrefix,
      @Nullable List<PickItem> items,
      @Nullable String availableTitle,
      @Nullable String selectedTitle,
      @Nullable String filterPlaceholder,
      @Nullable String emptyText,
      @Nullable String saveLabel,
      @Nullable String action,
      @Nullable Map<String, Object> formAttrs) {

    List<PickItem> list() {
      return items == null ? List.of() : items;
    }

    /**
     * A checkbox id.
     *
     * @param v the item value
     * @return the id
     */
    public String itemId(String v) {
      return Markup.id((itemIdPrefix == null ? "" : itemIdPrefix) + v);
    }

    /**
     * Declares the positional initial value of the picked signal: one slot per checkbox in DOM
     * order, the value when picked, the empty string when not.
     *
     * @return the expression
     */
    public String signals() {
      List<String> slots = new java.util.ArrayList<>();
      for (PickItem it : list()) {
        slots.add(it.checked() ? "'" + Markup.id(it.value()) + "'" : "''");
      }
      String arr = slots.isEmpty() ? "[]" : "[" + String.join(", ", slots) + "]";
      return "{" + Markup.signal(signal) + ": " + arr + ", " + Markup.signal(filterSignal) + ": ''}";
    }

    /**
     * The form's other attributes.
     *
     * @return the attributes
     */
    public Map<String, Object> formAttributes() {
      Map<String, Object> a = new java.util.LinkedHashMap<>();
      if (Markup.nz(action) != null) {
        a.put("method", "post");
        a.put("action", action);
      }
      if (formAttrs != null) {
        a.putAll(formAttrs);
      }
      return a;
    }

    /**
     * Shows a row while it matches the filter text.
     *
     * @return the expression
     */
    public String filterExpr() {
      String q = "$" + Markup.signal(filterSignal);
      return "!" + q + ".trim() || el.dataset.filterText.includes(" + q + ".trim().toLowerCase())";
    }

    /**
     * How many entries start picked.
     *
     * @return the count
     */
    public int picked() {
      int n = 0;
      for (PickItem it : list()) {
        if (it.checked()) {
          n++;
        }
      }
      return n;
    }

    /**
     * The lower-cased text a row is filtered by.
     *
     * @param it the entry
     * @return name and code
     */
    public static String filterText(PickItem it) {
      return ((it.name() == null ? "" : it.name()) + " " + (it.code() == null ? "" : it.code()))
          .toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * multi-select.md: a checklist in a details element whose checkboxes share one name.
   *
   * @param id derives the parts: -field, -label, -summary, -options
   * @param name the shared checkbox name
   * @param label the label text
   * @param options the options
   * @param selected the chosen values
   * @param placeholder the summary when nothing is chosen
   * @param maxLabels how many chosen labels are listed, default 2
   * @param manyLabel the summary above that, {0} is the count, default {0} selected
   * @param search adds a search box bound to the signal
   * @param signal the search signal
   * @param emptyText shown without options
   * @param open starts open
   * @param optionAttrs attributes for every checkbox
   * @param describedBy the id of the help text
   * @param cls extra classes
   */
  public record MultiSelect(
      String id,
      @Nullable String name,
      @Nullable String label,
      @Nullable List<Option> options,
      @Nullable List<String> selected,
      @Nullable String placeholder,
      int maxLabels,
      @Nullable String manyLabel,
      boolean search,
      @Nullable String signal,
      @Nullable String emptyText,
      boolean open,
      @Nullable Map<String, Object> optionAttrs,
      @Nullable String describedBy,
      @Nullable String cls) {

    /** Recomputes the summary in the browser after any change. */
    public static final String SUMMARY_EXPR =
        "const c = [...el.querySelectorAll('.fc-multiselect-option input:checked')].map(i => i.nextElementSibling.textContent); el.querySelector('.fc-multiselect-summary').textContent = c.length === 0 ? el.dataset.placeholder : c.length <= +el.dataset.maxLabels ? c.join(', ') : el.dataset.manyLabel.replace('{0}', c.length)";

    /**
     * The id of the summary part.
     *
     * @param id the multi-select id
     * @return the id
     */
    public static String summaryId(String id) {
      return Markup.id(id) + "-summary";
    }

    /**
     * The id of the options part.
     *
     * @param id the multi-select id
     * @return the id
     */
    public static String optionsId(String id) {
      return Markup.id(id) + "-options";
    }

    List<Option> list() {
      return options == null ? List.of() : options;
    }

    boolean checked(String value) {
      return selected != null && selected.contains(value);
    }

    int labelLimit() {
      return maxLabels <= 0 ? 2 : maxLabels;
    }

    /**
     * The trigger's text: the placeholder, the chosen labels in option order, or the many label.
     *
     * @return the summary
     */
    public String summary() {
      List<String> labels = new java.util.ArrayList<>();
      for (Option o : list()) {
        if (checked(o.value())) {
          labels.add(o.label());
        }
      }
      if (labels.isEmpty()) {
        return placeholder == null ? "" : placeholder;
      }
      if (labels.size() <= labelLimit()) {
        return String.join(", ", labels);
      }
      return Markup.or(manyLabel, "{0} selected").replace("{0}", Integer.toString(labels.size()));
    }

    /**
     * The details element's attributes.
     *
     * @return the attributes
     */
    public Map<String, Object> detailsAttrs() {
      Map<String, Object> a = new java.util.LinkedHashMap<>();
      a.put("data-placeholder", placeholder == null ? "" : placeholder);
      a.put("data-max-labels", Integer.toString(labelLimit()));
      a.put("data-many-label", Markup.or(manyLabel, "{0} selected"));
      a.put("data-on:change", SUMMARY_EXPR);
      if (open) {
        a.put("open", true);
      }
      return a;
    }

    /**
     * Shows an option while it matches the search text.
     *
     * @return the expression
     */
    public String filterExpr() {
      String s = "$" + Markup.signal(signal);
      return "!" + s + ".trim() || el.dataset.filterText.includes(" + s + ".trim().toLowerCase())";
    }

    /**
     * The attributes of one option's label.
     *
     * @param o the option
     * @return the attributes
     */
    public Map<String, Object> optionLabelAttrs(Option o) {
      if (!search) {
        return Map.of();
      }
      String text =
          o.label().equals(o.value())
              ? o.label().toLowerCase(java.util.Locale.ROOT)
              : (o.label() + " " + o.value()).toLowerCase(java.util.Locale.ROOT);
      return Map.of("data-filter-text", text, "data-show", filterExpr());
    }

    /**
     * The search box's bind and the panel's signal attributes.
     *
     * @param kind data-signals or data-bind
     * @return the attributes
     */
    public Map<String, Object> signalAttrs(String kind) {
      String key = kind + ":" + Markup.signalAttr(Markup.signal(signal));
      return kind.equals("data-bind") ? Map.of(key, true) : Map.of(key, "''");
    }
  }

  /**
   * code-chips.md: an event-type code as coloured segments.
   *
   * @param code split on colons; each segment is escaped
   * @param segmentAttrs attributes for the segment at the same position
   * @param cls extra classes
   */
  public record CodeChips(
      @Nullable String code,
      @Nullable List<Map<String, Object>> segmentAttrs,
      @Nullable String cls) {}

  /**
   * code-block.md: code on a dark, scrollable, focusable panel. The code is the default slot.
   *
   * @param id the element id
   * @param label the aria label
   * @param cls extra classes
   */
  public record CodeBlock(@Nullable String id, @Nullable String label, @Nullable String cls) {}

  // ---- app shell

  /**
   * One sidebar entry: a link, or (with children) an expandable group.
   *
   * @param label the text
   * @param href the link
   * @param icon the icon
   * @param current marks the page's own entry
   * @param open starts a group expanded
   * @param children a group's entries
   */
  public record NavItem(
      @Nullable String label,
      @Nullable String href,
      @Nullable String icon,
      boolean current,
      boolean open,
      @Nullable List<NavItem> children) {}

  /**
   * A labelled group of sidebar entries.
   *
   * @param label the heading
   * @param items the entries
   */
  public record NavGroup(@Nullable String label, @Nullable List<NavItem> items) {}

  /**
   * The signed-in user shown in the sidebar footer and the user menu.
   *
   * @param name the name
   * @param email the email
   * @param initials the avatar text
   */
  public record ShellUser(
      @Nullable String name, @Nullable String email, @Nullable String initials) {}

  /**
   * app-shell.md (also the props of its sidebar and user-menu parts).
   *
   * @param logoHref the logo's link
   * @param nav the navigation groups
   * @param user the user
   * @param version the version text
   * @param profileHref the profile link
   * @param resetPasswordHref empty hides the item (IdP-managed users)
   * @param signOutAction the POST target of the Sign Out form
   * @param signOutAttrs attributes for the Sign Out form
   * @param theme system, light or dark; non-empty renders the theme picker
   */
  public record AppShell(
      @Nullable String logoHref,
      @Nullable List<NavGroup> nav,
      @Nullable ShellUser user,
      @Nullable String version,
      @Nullable String profileHref,
      @Nullable String resetPasswordHref,
      @Nullable String signOutAction,
      @Nullable Map<String, Object> signOutAttrs,
      @Nullable String theme) {}

  // ---- document

  /**
   * The full HTML document around a page.
   *
   * @param title the page title
   * @param htmlAttrs attributes of the html element (theme and shell signals, the dark class)
   * @param cssUrl the stylesheet
   * @param jsUrl the Datastar script, loaded as a module
   * @param loginPath when set, a request answered 401 navigates there (with a return path); a local
   *     path
   */
  public record Document(
      String title,
      @Nullable Map<String, Object> htmlAttrs,
      String cssUrl,
      String jsUrl,
      @Nullable String loginPath) {

    /**
     * The handler that sends a user whose session expired to the sign-in page.
     *
     * @return the expression, or null when no login path is set
     */
    public @Nullable String sessionExpiredExpr() {
      if (loginPath == null) {
        return null;
      }
      return "evt.detail.type === 'error' && evt.detail.argsRaw.status === '401' && window.location.assign('"
          + Markup.path(loginPath)
          + "?redirect=' + encodeURIComponent(location.pathname + location.search))";
    }
  }
}
