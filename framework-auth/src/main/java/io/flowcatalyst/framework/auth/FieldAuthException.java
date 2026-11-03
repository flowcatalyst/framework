package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Response;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An answer to a request that used the {@code DPoP} scheme (contract section 15.3): the error
 * envelope plus {@code WWW-Authenticate} (when the credential was refused) and the fresh {@code
 * DPoP-Nonce}, which every answer to such a request carries.
 */
final class FieldAuthException extends HttpException {

  private static final long serialVersionUID = 1L;

  private final transient @Nullable String challenge;
  private final String nonce;

  FieldAuthException(int status, String code, String message, @Nullable String challenge, String nonce) {
    super(status, code, message, Map.of());
    this.challenge = challenge;
    this.nonce = nonce;
  }

  @Override
  public Response toResponse() {
    Response response = super.toResponse().header("DPoP-Nonce", nonce).header("Cache-Control", "no-store");
    String c = challenge;
    return c == null ? response : response.header("WWW-Authenticate", c);
  }
}
