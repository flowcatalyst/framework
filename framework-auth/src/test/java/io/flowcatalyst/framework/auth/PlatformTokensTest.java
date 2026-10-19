package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.PrincipalKind;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Access-token verification on its own (contract section 5.6) and the key cache (section 5.2). */
class PlatformTokensTest {

  MutableClock clock;
  TestOidcProvider provider;
  PlatformTokens tokens;

  @BeforeEach
  void start() {
    clock = new MutableClock(Instant.parse("2026-10-13T08:00:00Z"));
    provider = TestOidcProvider.start(clock);
    tokens = PlatformTokens.createAllowingPlainHttp(provider.issuer(), Duration.ofSeconds(5), clock);
  }

  @AfterEach
  void stop() {
    provider.close();
  }

  private String invalid(String token) {
    TokenResult r = tokens.verify(token);
    assertThat(r).isInstanceOf(TokenResult.Invalid.class);
    return ((TokenResult.Invalid) r).reason();
  }

  private static Map<String, Object> change(String claim, Object value) {
    Map<String, Object> m = new HashMap<>();
    m.put(claim, value);
    return m;
  }

  @Test
  void anIdentityTokenFromADefaultLoginClientIsRefused() {
    assertThat(invalid(provider.accessToken(Map.of("token_use", "identity")))).isEqualTo("token_use");
    assertThat(invalid(provider.accessToken(change("token_use", null)))).isEqualTo("token_use");
  }

  @Test
  void anIdTokenIsNotAnAccessToken() {
    // an id_token's audience is the login client, not the platform
    assertThat(invalid(provider.accessToken(Map.of("aud", "app-client")))).isEqualTo("aud");
  }

  @Test
  void anotherIssuerOrAudienceIsRefused() {
    assertThat(invalid(provider.accessToken(Map.of("iss", "https://evil.example")))).isEqualTo("iss");
    assertThat(invalid(provider.accessToken(Map.of("aud", "https://evil.example")))).isEqualTo("aud");
    assertThat(tokens.verify(provider.accessToken(Map.of("aud", List.of("x", provider.issuer().toString())))))
        .isInstanceOf(TokenResult.Valid.class);
  }

  @Test
  void timesAreChecked() {
    long now = clock.instant().getEpochSecond();
    assertThat(invalid(provider.accessToken(Map.of("exp", now - 61)))).isEqualTo("expired");
    assertThat(invalid(provider.accessToken(Map.of("nbf", now + 61)))).isEqualTo("not_yet_valid");
    assertThat(tokens.verify(provider.accessToken(Map.of("exp", now - 59)))).isInstanceOf(TokenResult.Valid.class);
  }

  @Test
  void malformedTokensAreRefused() {
    assertThat(invalid("")).isEqualTo("malformed");
    assertThat(invalid("a.b")).isEqualTo("malformed");
    assertThat(invalid("a.b.c.d")).isEqualTo("malformed");
    assertThat(invalid("x".repeat(16_385))).isEqualTo("malformed");
    String good = provider.accessToken(Map.of());
    String[] p = good.split("\\.");
    assertThat(invalid(p[0] + "=." + p[1] + "." + p[2])).isEqualTo("malformed");
    assertThat(invalid("bm90IGpzb24." + p[1] + "." + p[2])).isEqualTo("malformed");
  }

  @Test
  void aValidTokenSaysWhoAndWhat() {
    String token =
        provider.accessToken(
            Map.of("sub", "prn_SVC", "scope", "ingestor:ingestion:manage ingestor:ingestion:view", "clients", List.of("clt_1:acme"), "client_id", "clt_1"));
    TokenResult r = tokens.verify(token);
    assertThat(r).isInstanceOf(TokenResult.Valid.class);
    AccessToken a = ((TokenResult.Valid) r).token();
    assertThat(a.principal().id().value()).isEqualTo("prn_SVC");
    assertThat(a.principal().kind()).isEqualTo(PrincipalKind.SERVICE);
    assertThat(a.hasScope("ingestor:ingestion:manage")).isTrue();
    assertThat(a.hasScope("ingestor:ingestion")).isFalse();
    assertThat(a.mayActFor("clt_1")).isTrue();
    assertThat(a.mayActFor("acme")).isTrue();
    assertThat(a.mayActFor("clt_2")).isFalse();
    assertThat(a.mayActFor("clt")).isFalse();
    assertThat(a.mayActFor("")).isFalse();
    assertThat(a.authorizedParty()).isEqualTo("svc-client");
  }

  @Test
  void mayUseMatchesTheApplicationCodeOrStar() {
    AccessToken a = valid(Map.of("applications", List.of("app_1:orders", "billing")));
    assertThat(a.mayUse("orders")).isTrue();
    assertThat(a.mayUse("billing")).isTrue();
    assertThat(a.mayUse("app_1")).isFalse();
    assertThat(a.mayUse("order")).isFalse();
    assertThat(a.mayUse("")).isFalse();
    assertThat(valid(Map.of("applications", List.of("*"))).mayUse("anything")).isTrue();
    assertThat(valid(Map.of("applications", List.of())).mayUse("orders")).isFalse();
  }

  private AccessToken valid(Map<String, Object> claims) {
    return ((TokenResult.Valid) tokens.verify(provider.accessToken(claims))).token();
  }

  @Test
  void unknownKidsTriggerAtMostOneKeyReadAMinute() {
    assertThat(tokens.verify(provider.accessToken(Map.of()))).isInstanceOf(TokenResult.Valid.class);
    assertThat(provider.keyFetches()).isEqualTo(1);
    String forged = provider.accessToken(Map.of());
    String[] p = forged.split("\\.");
    // the keys were read a moment ago: an unknown kid does not read them again yet
    String early = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\",\"kid\":\"early\"}".getBytes());
    assertThat(invalid(early + "." + p[1] + "." + p[2])).isEqualTo("unknown_kid");
    assertThat(provider.keyFetches()).isEqualTo(1);
    clock.advance(Duration.ofSeconds(61));
    for (int i = 0; i < 50; i++) {
      String header = java.util.Base64.getUrlEncoder().withoutPadding()
          .encodeToString(("{\"alg\":\"RS256\",\"kid\":\"made-up-" + i + "\"}").getBytes());
      assertThat(invalid(header + "." + p[1] + "." + p[2])).isEqualTo("unknown_kid");
    }
    assertThat(provider.keyFetches()).isEqualTo(2);
    clock.advance(Duration.ofSeconds(61));
    provider.rotateKey();
    // a token under the new kid after the minute is up: one more read, and it is valid
    assertThat(tokens.verify(provider.accessToken(Map.of()))).isInstanceOf(TokenResult.Valid.class);
    assertThat(provider.keyFetches()).isEqualTo(3);
  }

  @Test
  void manyRequestsWithUnknownKidsAtOnceReadTheKeysOnce() throws Exception {
    tokens.verify(provider.accessToken(Map.of()));
    clock.advance(Duration.ofSeconds(61));
    String[] p = provider.accessToken(Map.of()).split("\\.");
    int threads = 32;
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    java.util.List<java.util.concurrent.Future<TokenResult>> results = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"alg\":\"RS256\",\"kid\":\"x" + i + "\"}").getBytes());
      results.add(pool.submit(() -> {
        go.await();
        return tokens.verify(header + "." + p[1] + "." + p[2]);
      }));
    }
    go.countDown();
    for (var f : results) {
      assertThat(f.get()).isInstanceOf(TokenResult.Invalid.class);
    }
    pool.shutdown();
    assertThat(provider.keyFetches()).isEqualTo(2);
  }

  @Test
  void keysAreReadAgainAfterAnHourAndKeptWhenThePlatformIsDown() {
    tokens.verify(provider.accessToken(Map.of()));
    clock.advance(Duration.ofMinutes(61));
    provider.down(true);
    // the read after an hour fails (one attempt); the keys already read keep working
    assertThat(tokens.verify(provider.accessToken(Map.of()))).isInstanceOf(TokenResult.Valid.class);
    assertThat(tokens.verify(provider.accessToken(Map.of()))).isInstanceOf(TokenResult.Valid.class);
    assertThat(provider.keyFetches()).isEqualTo(2);
    provider.down(false);
    clock.advance(Duration.ofMinutes(2));
    tokens.verify(provider.accessToken(Map.of()));
    assertThat(provider.keyFetches()).isEqualTo(3);
  }

  @Test
  void withNoKeysReadAndThePlatformDownATokenIsUncheckableNotInvalid() {
    provider.down(true);
    String token = provider.accessToken(Map.of());
    assertThat(tokens.verify(token)).isInstanceOf(TokenResult.Unavailable.class);
    int discoveries = provider.discoveryFetches();
    // within five seconds the platform is not called again
    assertThat(tokens.verify(token)).isInstanceOf(TokenResult.Unavailable.class);
    assertThat(provider.discoveryFetches()).isEqualTo(discoveries);
    provider.down(false);
    clock.advance(Duration.ofSeconds(6));
    assertThat(tokens.verify(token)).isInstanceOf(TokenResult.Valid.class);
  }

  @Test
  void forEnvironmentAllowsPlainHttpOnlyForLocalAndTest() {
    var http = java.net.URI.create("http://localhost:8080");
    var timeout = Duration.ofSeconds(1);
    org.assertj.core.api.Assertions.assertThatCode(() -> PlatformTokens.forEnvironment("local", http, timeout, java.time.InstantSource.system())).doesNotThrowAnyException();
    org.assertj.core.api.Assertions.assertThatCode(() -> PlatformTokens.forEnvironment("test", http, timeout, java.time.InstantSource.system())).doesNotThrowAnyException();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> PlatformTokens.forEnvironment("production", http, timeout, java.time.InstantSource.system()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forEnvironment");
  }
}
