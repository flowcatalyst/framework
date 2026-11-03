package io.flowcatalyst.framework.auth;

/** What {@link FieldTokens#verify} found: valid, invalid, or not checkable right now. */
public sealed interface FieldTokenResult {

  /**
   * A field access token that passed every check of contract section 15.2.
   *
   * @param worker who it is for
   * @param jkt the thumbprint of the device key the token is bound to ({@code cnf.jkt}); the proof
   *     must be signed by that key
   */
  record Valid(FieldPrincipal worker, String jkt) implements FieldTokenResult {}

  /**
   * Not to be trusted: answer {@code 401}, except reason {@code pcr}, which is {@code 403
   * PIN_CHANGE_REQUIRED}.
   *
   * @param reason the contract's reason name (15.2, or 5.5 for the signature checks), safe to log,
   *     never the token
   */
  record Invalid(String reason) implements FieldTokenResult {}

  /**
   * The platform's keys cannot be read, so the token cannot be checked: answer {@code 503}.
   *
   * @param reason what failed, safe to log
   */
  record Unavailable(String reason) implements FieldTokenResult {}
}
