package io.flowcatalyst.framework.ui;

import gg.jte.Content;
import io.flowcatalyst.framework.http.datastar.ElementPatchMode;
import io.flowcatalyst.framework.http.datastar.PatchElementsOptions;
import io.flowcatalyst.framework.http.datastar.ServerSentEventGenerator;
import io.flowcatalyst.framework.http.Json;
import io.flowcatalyst.framework.ui.kit.Kit;
import io.flowcatalyst.framework.ui.kit.Markup;
import io.flowcatalyst.framework.ui.kit.Props;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The patches of one Datastar answer: kit {@link Content} rendered to markup and written as {@code
 * datastar-patch-elements} events, plus the flash message and signal helpers. Handed to the
 * callback of {@link Views#stream}.
 *
 * <p>Follow the kit's answering rules (conventions: a handled request always answers 200 with at
 * least one patch; an expected failure patches the error where it belongs).
 */
public final class Patches {

  private final ServerSentEventGenerator sse;
  private final String flashStackId;

  Patches(ServerSentEventGenerator sse, String flashStackId) {
    this.sse = sse;
    this.flashStackId = flashStackId;
  }

  /**
   * Replaces the element(s) with the same id as the root(s) of the content (morph).
   *
   * @param content markup whose root elements carry ids
   */
  public void outer(Content content) {
    sse.patchElements(Markup.render(content));
  }

  /**
   * Replaces the page content (the {@code #fw-page} wrapper): what {@link Views#page} sends for a
   * Datastar request, for a handler that streams more than the page.
   *
   * @param content the page content
   */
  public void page(Content content) {
    outer(Views.wrap(content));
  }

  /**
   * Replaces the element matching the selector with the content (the element is removed and
   * inserted, not morphed). Use it for a modal {@code <dialog>} the answer must close: removing an
   * open modal takes it out of the top layer, whereas a morph that drops its {@code open} attribute
   * leaves it modal and blocks the page.
   *
   * @param selector a CSS selector, normally {@code #id}
   * @param content the new element
   */
  public void replace(String selector, Content content) {
    patch(selector, ElementPatchMode.REPLACE, content);
  }

  /**
   * Moves focus to the element with this id, once the patches before it have been applied (a
   * dialog that closed hands focus back to its trigger).
   *
   * @param id the element id
   */
  public void focus(String id) {
    String attrs =
        Markup.attrs(
            Map.of(
                "data-init",
                "requestAnimationFrame(() => document.getElementById('" + Markup.id(id) + "')?.focus()); el.remove()"));
    append("body", Markup.raw("<span hidden" + attrs + "></span>"));
  }

  /**
   * Closes the modal dialog with this id, by appending a self-removing element that does it when
   * Datastar processes it. That processing is asynchronous: a later patch in the same answer that
   * morphs the dialog can run first and leave it modal without its {@code open} attribute. When
   * the answer also re-renders the dialog, {@link #replace} it instead.
   *
   * @param dialogId the dialog's id
   */
  public void closeDialog(String dialogId) {
    String attrs = Markup.attrs(Map.of("data-init", "document.getElementById('" + Markup.id(dialogId) + "').close(); el.remove()"));
    append("body", Markup.raw("<span hidden" + attrs + "></span>"));
  }

  /**
   * Replaces the inside of the element matching the selector.
   *
   * @param selector a CSS selector, normally {@code #id}
   * @param content the new children
   */
  public void inner(String selector, Content content) {
    patch(selector, ElementPatchMode.INNER, content);
  }

  /**
   * Appends to the element matching the selector.
   *
   * @param selector a CSS selector
   * @param content the new last children
   */
  public void append(String selector, Content content) {
    patch(selector, ElementPatchMode.APPEND, content);
  }

  /**
   * Prepends to the element matching the selector.
   *
   * @param selector a CSS selector
   * @param content the new first children
   */
  public void prepend(String selector, Content content) {
    patch(selector, ElementPatchMode.PREPEND, content);
  }

  private void patch(String selector, ElementPatchMode mode, Content content) {
    sse.patchElements(Markup.render(content), PatchElementsOptions.defaults().selector(selector).mode(mode));
  }

  /**
   * Removes the element matching the selector.
   *
   * @param selector a CSS selector
   */
  public void remove(String selector) {
    sse.remove(selector);
  }

  /**
   * Appends a flash message to the page's flash stack (flash-stack.md).
   *
   * @param kind success, info, warn or error
   * @param summary the headline
   * @param detail the second line, or null
   */
  public void flash(String kind, String summary, @Nullable String detail) {
    append("#" + flashStackId, Kit.flashMessage(new Props.FlashMessage(kind, summary, detail, false, null)));
  }

  /**
   * Patches signals.
   *
   * @param signals names to JSON-encodable values
   */
  public void signals(Map<String, Object> signals) {
    sse.patchSignals(Json.mapper().writeValueAsString(signals));
  }
}
