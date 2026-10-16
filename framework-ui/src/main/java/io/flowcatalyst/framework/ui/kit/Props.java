package io.flowcatalyst.framework.ui.kit;

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
}
