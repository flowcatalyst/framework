package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The authentication of both receivers: the delivery signature, and the optional bearer gate
 * AND-ed with it. Runs before any handler sees the request; only the headers are read until they
 * pass, then the raw body once, and the handler gets those same bytes.
 *
 * <p>With {@code require-signature} true (the default) every delivery must be signed with the
 * configured secret; no secret fails closed. With it false, an unsigned delivery is accepted, and
 * so is any delivery when no secret is configured; a signed one is still verified when a secret
 * is configured. The bearer gate applies whenever a bearer token is configured.
 */
final class ReceiverGate {

  private static final Logger LOG = LoggerFactory.getLogger(ReceiverGate.class);

  static final String SIGNATURE_HEADER = "X-FlowCatalyst-Signature";
  static final String TIMESTAMP_HEADER = "X-FlowCatalyst-Timestamp";
  private static final String AUTHORIZATION = "Authorization";

  private final @Nullable Secret signingSecret;
  private final @Nullable Secret bearerToken;
  private final boolean requireSignature;
  private final InstantSource clock;

  ReceiverGate(
      @Nullable Secret signingSecret,
      @Nullable Secret bearerToken,
      boolean requireSignature,
      InstantSource clock) {
    this.signingSecret = signingSecret;
    this.bearerToken = bearerToken;
    this.requireSignature = requireSignature;
    this.clock = clock;
  }

  /**
   * Checks a request.
   *
   * @param request the request; its body is read (and cached) only after the header checks pass
   * @param route the route, for the log
   * @return a 401 response when refused, else empty
   */
  Optional<Response> refuse(Request request, String route) {
    DeliverySignature.Verdict verdict = verify(request);
    if (verdict instanceof DeliverySignature.Invalid(DeliverySignature.Failure failure)) {
      // Only the failure code and the framework's own correlation id: never header values.
      LOG.warn(
          "flowcatalyst: refused a delivery to {}: {} (correlation {})",
          route,
          failure,
          request.correlationId());
      return Optional.of(
          HttpErrors.error(
              401,
              "UNAUTHENTICATED",
              "the delivery could not be authenticated",
              Map.of("reason", failure.name())));
    }
    return Optional.empty();
  }

  DeliverySignature.Verdict verify(Request request) {
    for (String name : List.of(SIGNATURE_HEADER, TIMESTAMP_HEADER, AUTHORIZATION)) {
      if (request.headers(name).size() > 1) {
        return new DeliverySignature.Invalid(DeliverySignature.Failure.DUPLICATE_HEADER);
      }
    }
    String signature = request.header(SIGNATURE_HEADER);
    String timestamp = request.header(TIMESTAMP_HEADER);
    String authorization = request.header(AUTHORIZATION);
    String secret = reveal(signingSecret);
    String bearer = reveal(bearerToken);
    boolean unsigned = isEmpty(signature) && isEmpty(timestamp);
    if (!requireSignature && (unsigned || secret.isEmpty())) {
      return DeliverySignature.checkBearer(bearer, authorization);
    }
    return DeliverySignature.check(
        request::bodyBytes, signature, timestamp, secret, bearer, authorization, clock.instant());
  }

  private static String reveal(@Nullable Secret secret) {
    return secret == null ? "" : secret.reveal();
  }

  private static boolean isEmpty(@Nullable String value) {
    return value == null || value.isEmpty();
  }
}
