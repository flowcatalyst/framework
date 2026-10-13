package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every id_token rule of contract section 5.5 through the real callback: each bad token is refused
 * with its reason, and no session is created.
 */
class IdTokenVerificationTest {

  @TempDir Path dir;
  AuthApp t;

  @BeforeEach
  void start() {
    t = AuthApp.start(dir);
  }

  @AfterEach
  void stop() {
    t.close();
  }

  private void refused(String reason) {
    Browser b = t.browser();
    TestResponse r = b.get(b.local(b.startSignIn("/")));
    assertThat(r.status()).as(reason).isEqualTo(400);
    assertThat(r.tree().path("error").asString()).as(reason).isEqualTo("INVALID_ID_TOKEN");
    assertThat(r.tree().path("details").path("reason").asString()).isEqualTo(reason);
    assertThat(r.body()).doesNotContain("eyJ");
    assertThat(b.jar).as(reason).doesNotContainKey("__Host-fw_session");
    assertThat(b.get("/api/me").status()).isEqualTo(401);
  }

  private static Map<String, Object> change(String claim, Object value) {
    Map<String, Object> m = new HashMap<>();
    m.put(claim, value);
    return m;
  }

  @Test
  void algNoneIsRefusedBeforeAnyKeyIsLookedAt() {
    t.provider.idTokenSigning(TestOidcProvider.Signing.NONE);
    refused("alg");
  }

  @Test
  void hs256SignedWithThePublicKeyIsRefused() {
    t.provider.idTokenSigning(TestOidcProvider.Signing.HS256_WITH_PUBLIC_KEY);
    refused("alg");
  }

  @Test
  void otherAlgorithmsAreRefusedEvenWhenTheSignatureIsRs256() {
    t.provider.idTokenHeaderChanges(Map.of("alg", "RS512"));
    refused("alg");
    t.provider.idTokenHeaderChanges(Map.of("alg", "rs256"));
    refused("alg");
    t.provider.idTokenHeaderChanges(change("alg", null));
    refused("alg");
  }

  @Test
  void aCriticalHeaderIsRefused() {
    t.provider.idTokenHeaderChanges(Map.of("crit", List.of("exp")));
    refused("crit");
  }

  @Test
  void aTokenWithoutAKidOrWithAnUnknownKidIsRefused() {
    t.provider.idTokenHeaderChanges(change("kid", null));
    refused("kid");
    t.provider.idTokenHeaderChanges(Map.of("kid", "not-published"));
    refused("unknown_kid");
  }

  @Test
  void aKeyUrlInTheHeaderIsNeverFetched() {
    t.provider.idTokenHeaderChanges(Map.of("kid", "attacker", "jku", "http://127.0.0.1:1/jwks", "x5u", "http://127.0.0.1:1/x"));
    refused("unknown_kid");
  }

  @Test
  void aSignatureByAnotherKeyATamperedSignatureOrATamperedPayloadIsRefused() {
    t.provider.idTokenSigning(TestOidcProvider.Signing.OTHER_KEY);
    refused("signature");
    t.provider.idTokenSigning(TestOidcProvider.Signing.TAMPERED_SIGNATURE);
    refused("signature");
    t.provider.idTokenSigning(TestOidcProvider.Signing.TAMPERED_PAYLOAD);
    refused("signature");
  }

  @Test
  void anotherIssuerIsRefused() {
    t.provider.idTokenChanges(Map.of("iss", "https://evil.example"));
    refused("iss");
    t.provider.idTokenChanges(Map.of("iss", t.provider.issuer() + "/"));
    refused("iss");
  }

  @Test
  void anotherAudienceIsRefused() {
    t.provider.idTokenChanges(Map.of("aud", "other-client"));
    refused("aud");
    t.provider.idTokenChanges(Map.of("aud", t.provider.issuer().toString()));
    refused("aud");
    t.provider.idTokenChanges(Map.of("aud", 42));
    refused("aud");
  }

  @Test
  void severalAudiencesNeedAnAzpThatIsUs() {
    t.provider.idTokenChanges(change("aud", List.of(AuthApp.CLIENT_ID, "other")));
    Map<String, Object> noAzp = change("aud", List.of(AuthApp.CLIENT_ID, "other"));
    noAzp.put("azp", null);
    t.provider.idTokenChanges(noAzp);
    refused("azp");
    t.provider.idTokenChanges(Map.of("azp", "other"));
    refused("azp");
  }

  @Test
  void anExpiredTokenIsRefusedAfterTheAllowance() {
    long now = AuthApp.START.getEpochSecond();
    t.provider.idTokenChanges(Map.of("exp", now - 61));
    refused("expired");
    t.provider.idTokenChanges(change("exp", null));
    refused("expired");
    t.provider.idTokenChanges(Map.of("exp", "tomorrow"));
    refused("expired");
  }

  @Test
  void aTokenNotYetValidOrIssuedInTheFutureIsRefused() {
    long now = AuthApp.START.getEpochSecond();
    t.provider.idTokenChanges(Map.of("nbf", now + 61));
    refused("not_yet_valid");
    t.provider.idTokenChanges(Map.of("iat", now + 61));
    refused("iat");
  }

  @Test
  void theAllowanceIsSixtySecondsExactly() {
    long now = AuthApp.START.getEpochSecond();
    t.provider.idTokenChanges(Map.of("exp", now - 59, "nbf", now + 60, "iat", now + 60));
    Browser b = t.browser();
    assertThat(b.signIn("/").status()).isEqualTo(302);
    t.provider.idTokenChanges(Map.of("exp", now - 60));
    refused("expired");
  }

  @Test
  void aWrongOrMissingNonceIsRefused() {
    t.provider.idTokenChanges(Map.of("nonce", SessionHandles.random(32)));
    refused("nonce");
    t.provider.idTokenChanges(change("nonce", null));
    refused("nonce");
  }

  @Test
  void aTokenWithoutASubjectIsRefused() {
    t.provider.idTokenChanges(Map.of("sub", ""));
    refused("sub");
  }

  @Test
  void anAccessTokenIsNeverAcceptedAsAnIdToken() {
    t.provider.idTokenChanges(Map.of("token_use", "api"));
    refused("token_use");
  }

  @Test
  void theSessionCarriesWhatTheIdTokenSaid() {
    t.provider.signInAs(
        Map.of(
            "sub", "prn_ADA",
            "tier", "CLIENT",
            "client_id", "clt_1",
            "clients", List.of("clt_1:acme"),
            "applications", List.of("app_1:orders"),
            "roles", List.of("orders:clerk", 7)));
    Browser b = t.browser();
    assertThat(b.signIn("/").status()).isEqualTo(302);
    TestResponse s = b.get("/auth/session");
    assertThat(s.tree().path("principal").path("id").asString()).isEqualTo("prn_ADA");
    assertThat(s.tree().path("tier").asString()).isEqualTo("CLIENT");
    assertThat(s.tree().path("client").asString()).isEqualTo("acme");
    assertThat(s.tree().path("roles").toString()).isEqualTo("[\"orders:clerk\"]");
    assertThat(s.tree().path("permissions").toString()).isEqualTo("[\"orders:thing:create\",\"orders:thing:view\"]");
    assertThat(s.tree().path("authTime").asString()).isEqualTo("2026-10-13T08:00:00Z");
  }

  @Test
  void keysRotatedByThePlatformArePickedUp() {
    Browser b = t.browser();
    assertThat(b.signIn("/").status()).isEqualTo(302);
    t.provider.rotateKey();
    t.clock.advance(Duration.ofMinutes(2));
    Browser c = t.browser();
    assertThat(c.signIn("/").status()).isEqualTo(302);
  }
}
