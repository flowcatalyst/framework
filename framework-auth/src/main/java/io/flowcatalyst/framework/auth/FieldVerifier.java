package io.flowcatalyst.framework.auth;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A field request's credentials end to end (contract sections 15.2 to 15.4): the access token, the
 * DPoP proof, the application nonce and the replay cache, in that order. The nonce is checked only
 * when {@code nonces} is given; the platform's conformance vectors carry the platform's nonces, so
 * the vector test passes none.
 */
final class FieldVerifier {

  private static final Logger LOG = LoggerFactory.getLogger(FieldVerifier.class);

  /** The outcome. */
  sealed interface Outcome {
    /** Every check passed. */
    record Verified(FieldPrincipal worker) implements Outcome {}

    /** Refused: {@code 401 UNAUTHENTICATED}, {@code DPoP error="invalid_token"}. */
    record Refused(String reason) implements Outcome {}

    /** The proof's nonce is missing or not ours: {@code 401 USE_DPOP_NONCE}. */
    record NonceRequired() implements Outcome {}

    /** A temporary-PIN token: {@code 403 PIN_CHANGE_REQUIRED}. */
    record PinChangeRequired() implements Outcome {}

    /** The keys or the replay cache cannot be read: {@code 503 UNAVAILABLE}. */
    record Unavailable(String reason) implements Outcome {}
  }

  private final FieldTokens tokens;
  private final String publicBase;
  private final Duration proofWindow;
  private final @Nullable FieldNonces nonces;
  private final FieldReplayCache replay;
  private final InstantSource clock;

  FieldVerifier(
      FieldTokens tokens,
      String publicBase,
      Duration proofWindow,
      @Nullable FieldNonces nonces,
      FieldReplayCache replay,
      InstantSource clock) {
    this.tokens = tokens;
    this.publicBase = publicBase.endsWith("/") ? publicBase.substring(0, publicBase.length() - 1) : publicBase;
    this.proofWindow = proofWindow;
    this.nonces = nonces;
    this.replay = replay;
    this.clock = clock;
  }

  /**
   * Checks a request.
   *
   * @param token the access token (after the {@code DPoP} scheme)
   * @param proofs the values of the {@code DPoP} header
   * @param method the request method
   * @param path the request path, without query
   */
  Outcome verify(String token, List<String> proofs, String method, String path) {
    FieldTokenResult.Valid valid;
    switch (tokens.verify(token)) {
      case FieldTokenResult.Invalid i -> {
        return "pcr".equals(i.reason()) ? new Outcome.PinChangeRequired() : new Outcome.Refused(i.reason());
      }
      case FieldTokenResult.Unavailable u -> {
        return new Outcome.Unavailable(u.reason());
      }
      case FieldTokenResult.Valid v -> valid = v;
    }
    if (proofs.isEmpty() || proofs.getFirst().isBlank()) {
      return new Outcome.Refused("missing_proof");
    }
    if (proofs.size() > 1) {
      return new Outcome.Refused("invalid_proof");
    }
    FieldProofs.Checked checked =
        FieldProofs.verify(proofs.getFirst().trim(), token, valid.jkt(), method, publicBase + path, clock.instant(), proofWindow);
    FieldProofs.Checked.Ok proof;
    switch (checked) {
      case FieldProofs.Checked.Refused r -> {
        return new Outcome.Refused(r.reason());
      }
      case FieldProofs.Checked.Ok ok -> proof = ok;
    }
    FieldNonces required = nonces;
    if (required != null && !required.valid(proof.nonce())) {
      return new Outcome.NonceRequired();
    }
    try {
      if (!replay.firstUse(proof.jkt(), proof.jti(), FieldReplayCache.WINDOW)) {
        return new Outcome.Refused("replayed");
      }
    } catch (RuntimeException e) {
      LOG.warn("the field proof replay cache failed: {}", e.getClass().getSimpleName());
      return new Outcome.Unavailable("the replay cache cannot be reached");
    }
    return new Outcome.Verified(valid.worker());
  }
}
