package io.flowcatalyst.framework.auth;

import java.util.Map;

/**
 * Why a sign-in did not complete at {@code /auth/callback} (contract section 4.2).
 *
 * @param status the default status: 400 or 502
 * @param code {@code INVALID_CALLBACK}, {@code LOGIN_NOT_STARTED_HERE}, {@code INVALID_STATE},
 *     {@code LOGIN_REFUSED}, {@code LOGIN_FAILED}, {@code INVALID_ID_TOKEN} or {@code
 *     PLATFORM_UNAVAILABLE}
 * @param message a message for people; never contains a token, a code or the state
 * @param details the envelope's details, for example {@code reason} or {@code error}
 */
public record LoginFailure(int status, String code, String message, Map<String, String> details) {

  /** Copies the details. */
  public LoginFailure {
    details = Map.copyOf(details);
  }
}
