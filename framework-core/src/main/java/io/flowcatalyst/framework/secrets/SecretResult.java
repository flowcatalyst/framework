package io.flowcatalyst.framework.secrets;

import io.flowcatalyst.framework.config.Secret;

/** What a lookup found. Never carries a value outside a {@link Secret}. */
public sealed interface SecretResult {

  /**
   * The secret exists.
   *
   * @param secret the value
   */
  record Found(Secret secret) implements SecretResult {}

  /** There is no such secret for this client. */
  record NotFound() implements SecretResult {}

  /**
   * The store cannot hold a secret for this client or name (for example a client id the
   * {@code env} driver cannot encode).
   *
   * @param reason why, without any value
   */
  record Refused(String reason) implements SecretResult {}
}
