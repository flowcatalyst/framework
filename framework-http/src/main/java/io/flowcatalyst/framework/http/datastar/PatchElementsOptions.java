package io.flowcatalyst.framework.http.datastar;

import org.jspecify.annotations.Nullable;

/**
 * Options of {@link ServerSentEventGenerator#patchElements(String, PatchElementsOptions)}.
 *
 * @param selector a CSS selector for the target, or null to use the ids of the patched elements
 * @param mode how to apply the patch
 * @param useViewTransition whether to wrap the patch in a view transition
 * @param namespace the elements' namespace
 * @param eventId the SSE event id, or null
 * @param retryMillis the client's reconnect delay; the default is {@value ServerSentEventGenerator#DEFAULT_RETRY_MILLIS}
 */
public record PatchElementsOptions(
    @Nullable String selector,
    ElementPatchMode mode,
    boolean useViewTransition,
    Namespace namespace,
    @Nullable String eventId,
    long retryMillis) {

  /**
   * The defaults: no selector, outer, no view transition, HTML, no id, default retry.
   *
   * @return the options
   */
  public static PatchElementsOptions defaults() {
    return new PatchElementsOptions(
        null, ElementPatchMode.OUTER, false, Namespace.HTML, null, ServerSentEventGenerator.DEFAULT_RETRY_MILLIS);
  }

  /**
   * Sets the selector.
   *
   * @param value the CSS selector
   * @return a copy
   */
  public PatchElementsOptions selector(String value) {
    return new PatchElementsOptions(value, mode, useViewTransition, namespace, eventId, retryMillis);
  }

  /**
   * Sets the mode.
   *
   * @param value the mode
   * @return a copy
   */
  public PatchElementsOptions mode(ElementPatchMode value) {
    return new PatchElementsOptions(selector, value, useViewTransition, namespace, eventId, retryMillis);
  }

  /**
   * Sets the view transition flag.
   *
   * @param value true to use a view transition
   * @return a copy
   */
  public PatchElementsOptions useViewTransition(boolean value) {
    return new PatchElementsOptions(selector, mode, value, namespace, eventId, retryMillis);
  }

  /**
   * Sets the namespace.
   *
   * @param value the namespace
   * @return a copy
   */
  public PatchElementsOptions namespace(Namespace value) {
    return new PatchElementsOptions(selector, mode, useViewTransition, value, eventId, retryMillis);
  }

  /**
   * Sets the event id.
   *
   * @param value the id
   * @return a copy
   */
  public PatchElementsOptions eventId(String value) {
    return new PatchElementsOptions(selector, mode, useViewTransition, namespace, value, retryMillis);
  }

  /**
   * Sets the retry delay.
   *
   * @param millis the delay in milliseconds
   * @return a copy
   */
  public PatchElementsOptions retry(long millis) {
    return new PatchElementsOptions(selector, mode, useViewTransition, namespace, eventId, millis);
  }
}
