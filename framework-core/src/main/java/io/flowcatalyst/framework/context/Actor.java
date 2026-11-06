package io.flowcatalyst.framework.context;

import org.jspecify.annotations.Nullable;

/**
 * The operator behind a {@link Context} whose principal is someone else: a supervisor, or a
 * platform user, signed in as a field worker (sessions-and-login contract section 15.5). The
 * principal stays the subject (the worker); the actor is who actually did the work.
 *
 * @param id the actor's principal id ({@code fwu_...} or {@code prn_...}); not blank
 * @param type {@code field_worker} (a supervisor) or {@code principal} (a platform user)
 * @param name the actor's display name, or null
 */
public record Actor(String id, String type, @Nullable String name) {

  /**
   * Checks the id.
   *
   * @throws IllegalArgumentException when the id is blank
   */
  public Actor {
    if (id.isBlank()) {
      throw new IllegalArgumentException("an actor needs an id");
    }
  }
}
