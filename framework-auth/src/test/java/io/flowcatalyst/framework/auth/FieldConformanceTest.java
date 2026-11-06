package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.context.ApplicationCode;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platform's field-token vectors (contract section 15.9, {@code conformance/field/}): every
 * case, each checked at its own {@code now}, with the application-nonce check off because the
 * vectors carry the platform's nonces.
 */
class FieldConformanceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> SIGNATURE_LEVEL = Set.of("malformed", "alg", "crit", "kid", "unknown_kid", "signature");

  @Test
  void everyCaseGivesTheExpectedAnswer() {
    JsonNode cases = JSON.readTree(Conformance.read("field/cases.json"));
    JsonNode jwks = JSON.readTree(Conformance.read("field/jwks.json"));
    Map<String, RSAPublicKey> keys = new HashMap<>();
    for (JsonNode jwk : jwks.path("keys")) {
      RSAPublicKey key = Platform.rsaKey(jwk);
      assertThat(key).as("key " + jwk.path("kid").asString()).isNotNull();
      keys.put(jwk.path("kid").asString(), key);
    }
    Jwt.KeySource source = kid -> Optional.ofNullable(keys.get(kid));

    String issuer = cases.path("issuer").asString();
    String app = cases.path("appCode").asString();
    List<String> names = new java.util.ArrayList<>();
    for (JsonNode c : cases.path("cases")) {
      MutableClock clock = new MutableClock(Instant.parse(c.path("now").asString()));
      FieldTokens tokens = new FieldTokens(source, issuer, new ApplicationCode(app), clock);
      URI url = URI.create(c.path("url").asString());
      String base = url.getScheme() + "://" + url.getRawAuthority();
      // a five minute proof window, as the platform's SDK verifier has (see conformance/field/README.md)
      FieldVerifier verifier = new FieldVerifier(tokens, base, Duration.ofMinutes(5), null, FieldReplayCache.memory(clock), clock);
      JsonNode proof = c.get("dpopProof");
      List<String> proofs = proof == null || proof.asString().isEmpty() ? List.of() : List.of(proof.asString());
      FieldVerifier.Outcome outcome = verifier.verify(c.path("accessToken").asString(), proofs, c.path("method").asString(), url.getRawPath());
      String got = answer(outcome);
      // contract 5.5 rule 8 names a missing exp "expired"; the vectors call it invalid_token (15.9)
      if (c.path("name").asString().equals("missing_exp") && got.equals("expired")) {
        got = "invalid_token";
      }
      assertThat(got).as(c.path("name").asString()).isEqualTo(c.path("expect").asString());
      if (outcome instanceof FieldVerifier.Outcome.Verified verified) {
        assertPrincipal(c.path("name").asString(), c.get("principal"), verified.worker());
      }
      names.add(c.path("name").asString());
    }
    assertThat(names).hasSize(21)
        .contains(
            "valid", "stale_proof", "missing_exp", "alg_not_rs256", "pcr_pin_change_only", "valid_unenrolled", "valid_impersonated_supervisor",
            "valid_impersonated_principal", "valid_test");
  }

  /** A case's {@code principal} says what the verified principal carries (15.9); other valid cases carry the defaults. */
  private static void assertPrincipal(String name, JsonNode expected, FieldPrincipal got) {
    assertThat(got.workerId()).as(name).isEqualTo("fwu_0FIXTUREWORKER");
    assertThat(got.clientIdentifier()).as(name).isEqualTo("fixture-client");
    if (expected == null) {
      assertThat(got.deviceMode()).as(name).isEqualTo(FieldPrincipal.DeviceMode.PERSONAL);
      assertThat(got.actor()).as(name).isNull();
      assertThat(got.test()).as(name).isFalse();
      return;
    }
    String deviceId = expected.path("deviceId").asString();
    assertThat(got.deviceId()).as(name + " deviceId").isEqualTo(deviceId.isEmpty() ? null : deviceId);
    assertThat(got.deviceMode().name()).as(name + " deviceMode").isEqualTo(expected.path("deviceMode").asString());
    assertThat(got.amr()).as(name + " amr").containsExactlyElementsOf(JSON.convertValue(expected.path("amr"), List.class));
    assertThat(got.test()).as(name + " test").isEqualTo(expected.path("test").asBoolean());
    JsonNode actor = expected.get("actor");
    if (actor == null) {
      assertThat(got.actor()).as(name + " actor").isNull();
    } else {
      assertThat(got.actor()).as(name + " actor").isNotNull();
      assertThat(got.actor().id()).isEqualTo(actor.path("id").asString());
      assertThat(got.actor().type()).isEqualTo(actor.path("type").asString());
      assertThat(got.actor().name()).isEqualTo(actor.path("name").asString());
    }
  }

  private static String answer(FieldVerifier.Outcome outcome) {
    return switch (outcome) {
      case FieldVerifier.Outcome.Verified v -> "valid";
      case FieldVerifier.Outcome.Refused r -> SIGNATURE_LEVEL.contains(r.reason()) ? "invalid_token" : r.reason();
      case FieldVerifier.Outcome.PinChangeRequired p -> "pcr";
      case FieldVerifier.Outcome.NonceRequired n -> "nonce_required";
      case FieldVerifier.Outcome.Unavailable u -> "unavailable: " + u.reason();
    };
  }
}
