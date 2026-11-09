package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.http.Request;
import java.util.Optional;

/**
 * Authenticates a subscribe request the application's usual way (push contract, 6.1). Empty means
 * the request carries no credentials (it may still subscribe with tickets); a failure is thrown as
 * an {@code HttpException} and answered as it is (401, 403 for a CSRF refusal, 503). See
 * {@link PushAuth#authModule} for the auth module's.
 */
@FunctionalInterface
public interface PushAuthenticator {

  /**
   * Authenticates a request.
   *
   * @param request the subscribe request
   * @return the credentials, empty when the request carries none
   * @throws Exception as an {@code HttpException} to answer a bad credential
   */
  Optional<Credentials> authenticate(Request request) throws Exception;
}
