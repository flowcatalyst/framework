package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.http.Request;

/**
 * Chooses the client a request acts for (contract section 7.3). Whatever it returns, the framework
 * then checks that the principal may act for it ({@link Principal#mayActFor(ClientId)}), unless it
 * is {@link #PLATFORM}, and answers {@code 403 CLIENT_NOT_PERMITTED} otherwise.
 */
@FunctionalInterface
public interface ClientSelector {

  /** "No particular client": the same value as the receivers' platform client. */
  ClientId PLATFORM = new ClientId("_platform");

  /**
   * Chooses the client.
   *
   * @param principal the authenticated principal
   * @param request the request, for an application that takes the client from a path or header
   * @return the client; a {@link ClientId} holds the client's identifier (its code), not its
   *     platform id
   * @throws Exception any failure, which becomes a 500
   */
  ClientId select(Principal principal, Request request) throws Exception;

  /**
   * The default (contract section 7.3): the home client's identifier when the token names it; else,
   * when {@code clients} holds exactly one entry of the form {@code <id>:<identifier>}, that
   * identifier (a client service token: access tokens carry no home client); else {@link #PLATFORM}.
   *
   * @return the selector
   */
  static ClientSelector homeClient() {
    return (principal, request) -> {
      java.util.Optional<String> home = principal.homeClientIdentifier();
      if (home.isEmpty() && principal.clients().size() == 1) {
        String only = principal.clients().getFirst();
        int colon = only.indexOf(':');
        if (colon > 0 && colon < only.length() - 1) {
          home = java.util.Optional.of(only.substring(colon + 1));
        }
      }
      return home.filter(ClientSelector::valid).map(ClientId::new).orElse(PLATFORM);
    };
  }

  private static boolean valid(String value) {
    return value.matches("[A-Za-z0-9._-]{1,100}");
  }
}
