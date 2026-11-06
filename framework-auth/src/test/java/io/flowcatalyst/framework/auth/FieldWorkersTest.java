package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.auth.TestFieldDevice.Flaw;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract section 15: every refusal of 15.2 and 15.3 over the application's real routes, and 15.4 to 15.8. */
class FieldWorkersTest {

  static final String KEY_1 = CookieKeys.generate("k1");
  static final String KEY_2 = CookieKeys.generate("k2");
  static final String KEY_9 = CookieKeys.generate("k9");

  @TempDir Path dir;
  AuthApp t;
  TestFieldDevice device;

  @AfterEach
  void stop() {
    if (t != null) {
      t.close();
    }
  }

  void start() {
    start(b -> {}, KEY_1);
  }

  void start(Consumer<AuthModule.Builder> more, String cookieKeys) {
    t =
        AuthApp.start(
            dir,
            b -> {
              b.application(new ApplicationCode("orders")).fieldWorkers(FieldOptions.builder().build());
              more.accept(b);
            },
            Map.of("AUTH_COOKIE_KEYS", cookieKeys));
    device = TestFieldDevice.create(t.clock);
  }

  String token(Map<String, Object> changes) {
    return t.provider.fieldToken("orders", device.jkt(), changes);
  }

  String nonce() {
    return t.app.get(AuthModule.Services.class).requireField().nonces().issue();
  }

  TestResponse send(String method, String path, String token, String proof) {
    TestResponse r = t.client.request(Method.valueOf(method), path).header("Authorization", "DPoP " + token).header("DPoP", proof).send();
    return r;
  }

  /** A request with a valid proof and application nonce. */
  TestResponse get(String path, String token, Flaw... flaws) {
    return send("GET", path, token, device.proof("GET", AuthApp.ORIGIN + path, nonce(), token, flaws));
  }

  static void assertInvalidToken(TestResponse r) {
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().path("error").asString()).isEqualTo("UNAUTHENTICATED");
    assertThat(r.header("WWW-Authenticate")).isEqualTo("DPoP error=\"invalid_token\"");
    assertThat(r.header("DPoP-Nonce")).isNotBlank();
  }

  // ---- a valid request ------------------------------------------------------------------------

  @Test
  void aValidRequestBecomesAFieldWorkerContextAndCarriesAFreshNonce() {
    start();
    TestResponse r = get("/api/me", token(Map.of()));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().path("principal").asString()).isEqualTo("fwu_0TESTWORKER");
    assertThat(r.tree().path("principalKind").asString()).isEqualTo("FIELD_WORKER");
    assertThat(r.tree().path("client").asString()).isEqualTo("acme");
    assertThat(r.tree().path("application").asString()).isEqualTo("orders");
    assertThat(r.tree().path("source").asString()).isEqualTo("FIELD");
    // the default mapping grants nothing
    assertThat(r.tree().path("permissions")).isEmpty();
    // every answer to a DPoP request carries a nonce, the success included
    assertThat(r.header("DPoP-Nonce")).isNotBlank();
  }

  @Test
  void theFieldPartIsReadableAndTheOtherPartsAreEmpty() {
    start();
    FieldPrincipal worker = AuthTesting.fieldWorker("fwu_X", "acme", FieldPrincipal.DeviceMode.SHARED);
    AuthTesting auth = AuthTesting.of(t.app);
    AuthTesting.FieldRequest headers = auth.fieldRequest(worker, device, t.provider::signField, "GET", "/api/me");
    TestResponse r = t.client.get("/api/me").header("Authorization", headers.authorization()).header("DPoP", headers.dpop()).send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().path("principal").asString()).isEqualTo("fwu_X");

    Authenticated a = new Authenticated(
        new Principal(new io.flowcatalyst.framework.context.PrincipalId("fwu_X"), io.flowcatalyst.framework.context.PrincipalKind.FIELD_WORKER,
            null, null, null, null, List.of(), List.of(), List.of(), Set.of()),
        new ClientId("acme"), Set.of(), Authenticated.Source.FIELD, t.clock.instant(), null, null, worker);
    assertThat(a.field()).contains(worker);
    assertThat(a.principal().roles()).isEmpty();
    assertThat(a.principal().applications()).isEmpty();
    assertThat(a.principal().clients()).isEmpty();
    assertThat(a.principal().tier()).isNull();
  }

  @Test
  void aFieldPartWithoutTheFieldSourceIsRefused() {
    Throwable e =
        catchThrowable(
            () ->
                new Authenticated(
                    AuthTesting.user("prn_1"), new ClientId("acme"), Set.of(), Authenticated.Source.SESSION, AuthApp.START,
                    null, null, AuthTesting.fieldWorker("fwu_X", "acme", FieldPrincipal.DeviceMode.SHARED)));
    assertThat(e).isInstanceOf(IllegalArgumentException.class);
  }

  // ---- 15.2: the access token ----------------------------------------------------------------

  @Test
  void aFieldTokenAsBearerIsRefusedAndAnApiTokenAsDpopIsRefused() {
    start();
    String field = token(Map.of());
    TestResponse asBearer = t.client.get("/api/me").header("Authorization", "Bearer " + field).send();
    assertThat(asBearer.status()).isEqualTo(401);
    assertThat(asBearer.header("DPoP-Nonce")).isNull();

    String api = t.provider.accessToken(Map.of());
    TestResponse asDpop = send("GET", "/api/me", api, device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), api));
    assertInvalidToken(asDpop);
  }

  @Test
  void everyTokenRuleIsEnforced() {
    start();
    Map<String, Map<String, Object>> bad = new java.util.LinkedHashMap<>();
    bad.put("wrong aud", Map.of("aud", "other-app"));
    bad.put("aud as a list", Map.of("aud", List.of("orders")));
    bad.put("wrong issuer", Map.of("iss", "https://other.example"));
    bad.put("expired", Map.of("exp", t.clock.instant().minusSeconds(600).getEpochSecond()));
    bad.put("not yet valid", Map.of("nbf", t.clock.instant().plusSeconds(600).getEpochSecond()));
    bad.put("iat in the future", Map.of("iat", t.clock.instant().plusSeconds(600).getEpochSecond()));
    bad.put("wrong token_use", Map.of("token_use", "api"));
    bad.put("sub not fwu_", Map.of("sub", "prn_SOMEONE"));
    Map<String, Object> noClientId = new HashMap<>();
    noClientId.put("client_id", null);
    bad.put("no client_id", noClientId);
    Map<String, Object> noIdentifier = new HashMap<>();
    noIdentifier.put("client_identifier", null);
    bad.put("no client_identifier", noIdentifier);
    bad.put("client_identifier with a colon", Map.of("client_identifier", "a:b"));
    Map<String, Object> noSid = new HashMap<>();
    noSid.put("sid", null);
    bad.put("no sid", noSid);
    Map<String, Object> noCnf = new HashMap<>();
    noCnf.put("cnf", null);
    bad.put("no cnf", noCnf);
    Map<String, Object> noExp = new HashMap<>();
    noExp.put("exp", null);
    bad.put("no exp", noExp);
    bad.put("no jkt", Map.of("cnf", Map.of()));
    for (Map.Entry<String, Map<String, Object>> e : bad.entrySet()) {
      String token = token(e.getValue());
      assertInvalidToken(send("GET", "/api/me", token, device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), token)));
    }
  }

  @Test
  void aWrongTypeOrAlgorithmOrSignatureIsRefused() {
    start();
    // typ JWT instead of fc-field+jwt
    String plain = t.provider.sign(claimsOf(token(Map.of())));
    assertInvalidToken(send("GET", "/api/me", plain, device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), plain)));

    String good = token(Map.of());
    String[] p = good.split("\\.");
    String tampered = p[0] + "." + p[1] + "." + p[2].substring(0, p[2].length() - 4) + "AAAA";
    assertInvalidToken(send("GET", "/api/me", tampered, device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), tampered)));

    String hs256 = p[0].replace(p[0], java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"fc-field+jwt\",\"kid\":\"key-1\"}".getBytes()))
        + "." + p[1] + "." + p[2];
    assertInvalidToken(send("GET", "/api/me", hs256, device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), hs256)));
  }

  private Map<String, Object> claimsOf(String token) {
    String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
    return new HashMap<>(io.flowcatalyst.framework.http.Json.mapper().readValue(payload, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
  }

  @Test
  void aTemporaryPinTokenIsAnswered403PinChangeRequired() {
    start();
    TestResponse r = get("/api/me", token(Map.of("pcr", true)));
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().path("error").asString()).isEqualTo("PIN_CHANGE_REQUIRED");
    assertThat(r.header("DPoP-Nonce")).isNotBlank();
    // pcr false is an ordinary token
    assertThat(get("/api/me", token(Map.of("pcr", false))).status()).isEqualTo(200);
  }

  @Test
  void whenTheKeysCannotBeReadTheAnswerIs503() {
    start();
    String token = token(Map.of());
    String proof = device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), token);
    t.provider.down(true);
    TestResponse r = send("GET", "/api/me", token, proof);
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.tree().path("error").asString()).isEqualTo("UNAVAILABLE");
    assertThat(r.header("DPoP-Nonce")).isNotBlank();
  }

  // ---- 15.3: the proof -----------------------------------------------------------------------

  @Test
  void aMissingProofIsRefused() {
    start();
    TestResponse r = t.client.get("/api/me").header("Authorization", "DPoP " + token(Map.of())).send();
    assertInvalidToken(r);
  }

  @Test
  void twoProofHeadersAreRefused() {
    start();
    String token = token(Map.of());
    String a = device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), token);
    String b = device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), token);
    TestResponse r = t.client.get("/api/me").header("Authorization", "DPoP " + token).header("DPoP", a).header("DPoP", b).send();
    assertInvalidToken(r);
  }

  @Test
  void aProofThatBreaksAnyRuleIsRefused() {
    start();
    for (Flaw flaw :
        new Flaw[] {
          Flaw.OTHER_DEVICE_KEY, Flaw.BAD_SIGNATURE, Flaw.WRONG_METHOD, Flaw.WRONG_URL, Flaw.WRONG_ATH, Flaw.STALE, Flaw.NO_JTI, Flaw.WRONG_TYP,
          Flaw.WRONG_ALG, Flaw.PRIVATE_MEMBER
        }) {
      assertInvalidToken(get("/api/me", token(Map.of()), flaw));
    }
  }

  @Test
  void aProofSignedByAnotherKeyIsRefusedEvenWithTheRightNonce() {
    start();
    TestFieldDevice other = TestFieldDevice.create(t.clock);
    String token = token(Map.of());
    TestResponse r = send("GET", "/api/me", token, other.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), token));
    assertInvalidToken(r);
  }

  @Test
  void theHostHeaderNeverDecidesTheUrl() {
    start();
    String token = token(Map.of());
    String proof = device.proof("GET", "https://evil.example/api/me", nonce(), token);
    TestResponse r = t.client.get("/api/me").header("Authorization", "DPoP " + token).header("DPoP", proof).header("Host", "evil.example").send();
    assertInvalidToken(r);
  }

  @Test
  void aQueryStringIsNotPartOfTheUrl() {
    start();
    String token = token(Map.of());
    String proof = device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), token);
    TestResponse r = send("GET", "/api/me?x=1", token, proof);
    assertThat(r.status()).isEqualTo(200);
  }

  @Test
  void aReplayedProofIsRefused() {
    start();
    String token = token(Map.of());
    String proof = device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), token);
    assertThat(send("GET", "/api/me", token, proof).status()).isEqualTo(200);
    assertInvalidToken(send("GET", "/api/me", token, proof));
  }

  @Test
  void aRefusedProofIsNotRememberedAsUsed() {
    start();
    String token = token(Map.of());
    String proof = device.proof("GET", AuthApp.ORIGIN + "/api/me", "not-a-nonce", token, "same-jti");
    assertThat(send("GET", "/api/me", token, proof).status()).isEqualTo(401);
    String again = device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), token, "same-jti");
    assertThat(send("GET", "/api/me", token, again).status()).isEqualTo(200);
  }

  @Test
  void aDeviceWithABadClockIsAcceptedInsideTheWindowAndRefusedOutsideIt() {
    start();
    device = TestFieldDevice.create(() -> t.clock.instant().minus(Duration.ofHours(23)));
    assertThat(get("/api/me", token(Map.of())).status()).isEqualTo(200);
    device = TestFieldDevice.create(() -> t.clock.instant().plus(Duration.ofHours(23)));
    assertThat(get("/api/me", token(Map.of())).status()).isEqualTo(200);
    device = TestFieldDevice.create(() -> t.clock.instant().minus(Duration.ofHours(25)));
    assertInvalidToken(get("/api/me", token(Map.of())));
    device = TestFieldDevice.create(() -> t.clock.instant().plus(Duration.ofHours(25)));
    assertInvalidToken(get("/api/me", token(Map.of())));
  }

  // ---- 15.4: nonces --------------------------------------------------------------------------

  @Test
  void aMissingNonceIsUseDpopNonceWithAFreshOneThatWorksOnTheRetry() {
    start();
    String token = token(Map.of());
    TestResponse first = send("GET", "/api/me", token, device.proof("GET", AuthApp.ORIGIN + "/api/me", null, token));
    assertThat(first.status()).isEqualTo(401);
    assertThat(first.tree().path("error").asString()).isEqualTo("USE_DPOP_NONCE");
    assertThat(first.header("WWW-Authenticate")).isEqualTo("DPoP error=\"use_dpop_nonce\"");
    String offered = TestFieldDevice.nonceFrom(first.header("DPoP-Nonce"));
    TestResponse retry = send("GET", "/api/me", token, device.proof("GET", AuthApp.ORIGIN + "/api/me", offered, token));
    assertThat(retry.status()).isEqualTo(200);
  }

  @Test
  void aNonceFromTwoBucketsAgoIsRefusedButThePreviousBucketIsAccepted() {
    start();
    String old = nonce();
    t.clock.advance(Duration.ofSeconds(300));
    String token = token(Map.of());
    assertThat(send("GET", "/api/me", token, device.proof("GET", AuthApp.ORIGIN + "/api/me", old, token)).status()).isEqualTo(200);
    t.clock.advance(Duration.ofSeconds(300));
    String token2 = token(Map.of());
    TestResponse r = send("GET", "/api/me", token2, device.proof("GET", AuthApp.ORIGIN + "/api/me", old, token2));
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().path("error").asString()).isEqualTo("USE_DPOP_NONCE");
  }

  @Test
  void aNonceFromTheFutureOrFromGarbageIsRefused() {
    start();
    String token = token(Map.of());
    for (String bad : new String[] {"", "x", "AAAA", "A".repeat(40), "!!!!"}) {
      TestResponse r = send("GET", "/api/me", token, device.proof("GET", AuthApp.ORIGIN + "/api/me", bad, token));
      assertThat(r.tree().path("error").asString()).as(bad).isEqualTo("USE_DPOP_NONCE");
    }
  }

  @Test
  void aNonceUnderARotatedOutKeyIsRefusedAndOneUnderAStillConfiguredKeyIsAccepted() {
    start(b -> {}, KEY_2 + "," + KEY_1);
    FieldNonces rotatedOut = new FieldNonces(CookieKeys.parse(Secret.of(KEY_9)), t.clock);
    FieldNonces previous = new FieldNonces(CookieKeys.parse(Secret.of(KEY_1)), t.clock);
    String token = token(Map.of());
    TestResponse refused = send("GET", "/api/me", token, device.proof("GET", AuthApp.ORIGIN + "/api/me", rotatedOut.issue(), token));
    assertThat(refused.tree().path("error").asString()).isEqualTo("USE_DPOP_NONCE");
    TestResponse accepted = send("GET", "/api/me", token, device.proof("GET", AuthApp.ORIGIN + "/api/me", previous.issue(), token));
    assertThat(accepted.status()).isEqualTo(200);
    // and the application issues under the current (first) key
    String issued = t.app.get(AuthModule.Services.class).requireField().nonces().issue();
    assertThat(new FieldNonces(CookieKeys.parse(Secret.of(KEY_2)), t.clock).valid(issued)).isTrue();
    assertThat(previous.valid(issued)).isFalse();
  }

  @Test
  void aKnownAnswerPinsTheNonceConstruction() {
    // key: 32 bytes 0x00..0x1f (cookie key 'kv'); time 2026-11-03T12:00:00Z. Computed independently
    // (HKDF-SHA256 with a zero salt, then the HMAC in the contract); the Go side must give the same.
    byte[] key = new byte[32];
    for (int i = 0; i < 32; i++) {
      key[i] = (byte) i;
    }
    String secret = "kv:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    MutableClock clock = new MutableClock(java.time.Instant.parse("2026-11-03T12:00:00Z"));
    FieldNonces nonces = new FieldNonces(CookieKeys.parse(Secret.of(secret)), clock);
    String nonce = nonces.issue();
    byte[] raw = java.util.Base64.getUrlDecoder().decode(nonce);
    assertThat(raw).hasSize(24);
    assertThat(java.nio.ByteBuffer.wrap(raw, 0, 8).getLong()).isEqualTo(java.time.Instant.parse("2026-11-03T12:00:00Z").getEpochSecond() / 300);
    assertThat(nonce).isEqualTo(KNOWN_NONCE);
    // the HKDF output for that key (RFC 5869, zero salt, info "fw-field-nonce", 32 bytes)
    assertThat(java.util.HexFormat.of().formatHex(FieldNonces.hkdf(key))).isEqualTo(KNOWN_HKDF);
  }

  static final String KNOWN_HKDF = "81ebc15fe323fa767d99dbdbab2738d2590b3a5759203df8f9ce4b405c69e41a";
  static final String KNOWN_NONCE = "AAAAAABbO5BhEQP-hvoGRRcossz6o4Nz";

  // ---- 15.5: client, permissions, source ----------------------------------------------------

  @Test
  void theClientSelectorIsNotConsulted() {
    AtomicBoolean consulted = new AtomicBoolean();
    start(
        b ->
            b.clients(
                (principal, request) -> {
                  consulted.set(true);
                  return new ClientId("other");
                }),
        KEY_1);
    TestResponse r = get("/api/me", token(Map.of("client_identifier", "acme")));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().path("client").asString()).isEqualTo("acme");
    assertThat(consulted).isFalse();
  }

  @Test
  void permissionsComeFromTheFieldMappingOnly() {
    start(
        b ->
            b.permissions(PermissionMapper.byRole(Map.of("orders:clerk", Set.of("orders:thing:create"))))
                .fieldWorkers(FieldOptions.builder().permissions(FieldPermissions.all("orders:thing:view")).build()),
        KEY_1);
    TestResponse r = get("/api/me", token(Map.of()));
    assertThat(r.tree().path("permissions").toString()).isEqualTo("[\"orders:thing:view\"]");
    // a route that needs another permission refuses
    String token = token(Map.of());
    TestResponse create = t.client.post("/api/things").header("Authorization", "DPoP " + token)
        .header("DPoP", device.proof("POST", AuthApp.ORIGIN + "/api/things", nonce(), token)).jsonBody("{}").send();
    assertThat(create.status()).isEqualTo(403);
  }

  /** A POST to the route that needs {@code orders:thing:create}, with an optional X-Tenant header. */
  TestResponse createThing(String tenant) {
    String token = token(Map.of());
    var request = t.client.post("/api/things").header("Authorization", "DPoP " + token)
        .header("DPoP", device.proof("POST", AuthApp.ORIGIN + "/api/things", nonce(), token));
    if (tenant != null) {
      request = request.header("X-Tenant", tenant);
    }
    return request.jsonBody("{}").send();
  }

  @Test
  void aResolverSeesTheRequestAndItsPermissionsApply() {
    java.util.List<String> seen = new java.util.ArrayList<>();
    start(
        b ->
            b.fieldWorkers(
                FieldOptions.builder()
                    .permissionResolver(
                        (request, worker) -> {
                          seen.add(request.header("X-Tenant") + "|" + worker.workerId());
                          return Set.of("orders:thing:create");
                        })
                    .build()),
        KEY_1);
    assertThat(createThing("acme-db").status()).isEqualTo(201);
    assertThat(seen).containsExactly("acme-db|fwu_0TESTWORKER");
  }

  @Test
  void aResolverThatFailsAnswers503NotNoPermissions() {
    start(
        b ->
            b.fieldWorkers(
                FieldOptions.builder()
                    .permissionResolver(
                        (request, worker) -> {
                          throw new java.io.IOException("db is down");
                        })
                    .build()),
        KEY_1);
    TestResponse r = createThing(null);
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.tree().path("error").asString()).isEqualTo("UNAVAILABLE");
    assertThat(r.header("DPoP-Nonce")).isNotBlank();
    assertThat(r.header("WWW-Authenticate")).isNull();
    // a runtime failure is the same
    TestResponse me = get("/api/me", token(Map.of()));
    assertThat(me.status()).isEqualTo(503);
  }

  @Test
  void aResolverThatReturnsNothingGrantsNothing() {
    start(b -> b.fieldWorkers(FieldOptions.builder().permissionResolver((request, worker) -> Set.of()).build()), KEY_1);
    assertThat(createThing(null).status()).isEqualTo(403);
    stop();
    start(b -> b.fieldWorkers(FieldOptions.builder().permissionResolver((request, worker) -> null).build()), KEY_1);
    assertThat(createThing(null).status()).isEqualTo(403);
  }

  @Test
  void theMappingStillWorksAndTheLastOfTheTwoGivenWins() {
    start(b -> b.fieldWorkers(FieldOptions.builder().permissions(FieldPermissions.all("orders:thing:create")).build()), KEY_1);
    assertThat(createThing(null).status()).isEqualTo(201);
    stop();
    start(
        b ->
            b.fieldWorkers(
                FieldOptions.builder()
                    .permissionResolver((request, worker) -> { throw new IllegalStateException("x"); })
                    .permissions(FieldPermissions.all("orders:thing:create"))
                    .build()),
        KEY_1);
    assertThat(createThing(null).status()).isEqualTo(201);
  }

  @Test
  void byDeviceModeGivesSharedKiosksLess() {
    start(
        b -> b.fieldWorkers(FieldOptions.builder().permissions(FieldPermissions.byDeviceMode(Set.of("a:b:view", "a:b:edit"), Set.of("a:b:view"))).build()),
        KEY_1);
    TestResponse own = get("/api/me", token(Map.of("device_mode", "PERSONAL")));
    assertThat(own.tree().path("permissions").toString()).isEqualTo("[\"a:b:edit\",\"a:b:view\"]");
    TestResponse shared = get("/api/me", token(Map.of("device_mode", "SHARED")));
    assertThat(shared.tree().path("permissions").toString()).isEqualTo("[\"a:b:view\"]");
    // a mode that is neither gets the lesser set
    TestResponse unknown = get("/api/me", token(Map.of("device_mode", "KIOSK")));
    assertThat(unknown.tree().path("permissions").toString()).isEqualTo("[\"a:b:view\"]");
  }

  @Test
  void anUnenrolledTokenHasNoDeviceAndAnActorIsTheOperator() {
    start(
        b ->
            b.fieldWorkers(
                FieldOptions.builder()
                    .permissions(FieldPermissions.byDeviceMode(Map.of(FieldPrincipal.DeviceMode.UNENROLLED, Set.of("a:b:view"))))
                    .build()),
        KEY_1);
    Map<String, Object> changes = new HashMap<>();
    changes.put("device_id", null);
    changes.put("device_mode", "UNENROLLED");
    changes.put("act", Map.of("sub", "prn_ADMIN", "typ", "principal", "name", "Admin"));
    TestResponse r = get("/api/me", token(changes));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().path("principal").asString()).isEqualTo("fwu_0TESTWORKER");
    assertThat(r.tree().path("actor").asString()).isEqualTo("prn_ADMIN|principal|Admin");
    assertThat(r.tree().path("permissions").toString()).isEqualTo("[\"a:b:view\"]");
    // a mode with no mapping fails closed
    assertThat(get("/api/me", token(Map.of("device_mode", "PERSONAL"))).tree().path("permissions").toString()).isEqualTo("[]");
    // without an act claim there is no actor
    assertThat(get("/api/me", token(Map.of())).tree().path("actor").asString()).isEmpty();
    FieldTokens tokens = t.app.get(FieldTokens.class);
    assertThat(tokens.verify(token(changes))).isInstanceOfSatisfying(FieldTokenResult.Valid.class, v -> {
      assertThat(v.worker().deviceId()).isNull();
      assertThat(v.worker().deviceMode()).isEqualTo(FieldPrincipal.DeviceMode.UNENROLLED);
      assertThat(v.worker().actor()).isNotNull();
      assertThat(v.worker().test()).isFalse();
    });
  }

  @Test
  void requireSourceSeparatesFieldFromPeople() {
    start();
    String token = token(Map.of());
    assertThat(get("/api/field-only", token).status()).isEqualTo(200);
    TestResponse never = get("/api/people-only", token);
    assertThat(never.status()).isEqualTo(403);
    assertThat(never.tree().path("error").asString()).isEqualTo("FORBIDDEN");

    // a bearer token is not a field worker
    String api = t.provider.accessToken(Map.of("scope", ""));
    assertThat(t.client.get("/api/field-only").header("Authorization", "Bearer " + api).send().status()).isEqualTo(403);
    assertThat(t.client.get("/api/people-only").header("Authorization", "Bearer " + api).send().status()).isEqualTo(200);
  }

  // ---- 15.1: opt-in, no fall-back ------------------------------------------------------------

  @Test
  void withoutTheOptInTheDpopSchemeIsAnswered401WithoutANonce() {
    t = AuthApp.start(dir);
    device = TestFieldDevice.create(t.clock);
    String token = t.provider.fieldToken("orders", device.jkt(), Map.of());
    TestResponse r = t.client.get("/api/me").header("Authorization", "DPoP " + token).header("DPoP", device.proof("GET", AuthApp.ORIGIN + "/api/me", "x", token)).send();
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.header("DPoP-Nonce")).isNull();
  }

  @Test
  void aBadDpopRequestNeverFallsBackToTheSessionCookie() {
    start();
    t.provider.signInAs(Map.of("roles", List.of("orders:clerk")));
    Browser b = t.browser();
    assertThat(b.signIn("/").status()).isEqualTo(302);
    assertThat(b.get("/api/me").status()).isEqualTo(200);
    TestResponse r = b.get("/api/me", "Authorization", "DPoP junk", "DPoP", "junk");
    assertThat(r.status()).isEqualTo(401);
  }

  @Test
  void aDpopSchemeIsCaseInsensitive() {
    start();
    String token = token(Map.of());
    String proof = device.proof("GET", AuthApp.ORIGIN + "/api/me", nonce(), token);
    TestResponse r = t.client.get("/api/me").header("Authorization", "dpop " + token).header("DPoP", proof).send();
    assertThat(r.status()).isEqualTo(200);
  }

  @Test
  void aCsrfCheckDoesNotApplyToAFieldPost() {
    start(b -> b.permissions(PermissionMapper.none()).fieldWorkers(FieldOptions.builder().permissions(FieldPermissions.all("orders:thing:create")).build()), KEY_1);
    String token = token(Map.of());
    TestResponse r = t.client.post("/api/things").header("Authorization", "DPoP " + token)
        .header("DPoP", device.proof("POST", AuthApp.ORIGIN + "/api/things", nonce(), token))
        .header("Sec-Fetch-Site", "cross-site").jsonBody("{}").send();
    assertThat(r.status()).isEqualTo(201);
  }

  // ---- start-up checks and test support -------------------------------------------------------

  @Test
  void fieldWorkersNeedTheCookieKeys() {
    Throwable e =
        catchThrowable(() -> AuthApp.start(dir, b -> b.application(new ApplicationCode("orders")).fieldWorkers(FieldOptions.defaults()), Map.of()));
    assertThat(e).isInstanceOf(StartupException.class);
    assertThat(String.valueOf(e.getMessage())).contains("auth.cookie-keys");
  }

  @Test
  void fieldWorkersNeedTheApplicationCode() {
    Throwable e = catchThrowable(() -> AuthApp.start(dir, b -> b.fieldWorkers(FieldOptions.defaults()), Map.of("AUTH_COOKIE_KEYS", KEY_1)));
    assertThat(e).isInstanceOf(StartupException.class);
    assertThat(String.valueOf(e.getMessage())).contains("application code");
  }

  @Test
  void theMemoryReplayCacheWarnsOnceAtStartUp() {
    try (LogCapture log = LogCapture.start()) {
      start();
      String text = log.text();
      assertThat(text).contains("in-memory proof replay cache");
      assertThat(text.indexOf("in-memory proof replay cache")).isEqualTo(text.lastIndexOf("in-memory proof replay cache"));
    }
  }

  @Test
  void aReplayCacheFromTheOptionsIsUsed() {
    AtomicBoolean asked = new AtomicBoolean();
    start(
        b ->
            b.fieldWorkers(
                FieldOptions.builder()
                    .replayCache(
                        lookup -> (jkt, jti, ttl) -> {
                          asked.set(true);
                          assertThat(ttl).isEqualTo(Duration.ofMinutes(11));
                          return true;
                        })
                    .build()),
        KEY_1);
    assertThat(get("/api/me", token(Map.of())).status()).isEqualTo(200);
    assertThat(asked).isTrue();
  }

  @Test
  void aReplayCacheThatFailsClosesTheRequestWith503() {
    start(
        b ->
            b.fieldWorkers(
                FieldOptions.builder()
                    .replayCache(
                        lookup -> (jkt, jti, ttl) -> {
                          throw new IllegalStateException("redis is down");
                        })
                    .build()),
        KEY_1);
    TestResponse r = get("/api/me", token(Map.of()));
    assertThat(r.status()).isEqualTo(503);
  }

  @Test
  void memoryReplayCacheForgetsAfterTheWindow() {
    MutableClock clock = new MutableClock(AuthApp.START);
    FieldReplayCache cache = FieldReplayCache.memory(clock);
    assertThat(cache.firstUse("jkt", "a", FieldReplayCache.WINDOW)).isTrue();
    assertThat(cache.firstUse("jkt", "a", FieldReplayCache.WINDOW)).isFalse();
    assertThat(cache.firstUse("other", "a", FieldReplayCache.WINDOW)).isTrue();
    clock.advance(Duration.ofMinutes(12));
    assertThat(cache.firstUse("jkt", "a", FieldReplayCache.WINDOW)).isTrue();
  }

  @Test
  void manyDevicesProduceProofsTheVerifierAccepts() {
    // r and s with leading zero bytes or the high bit set exercise the DER conversion
    start();
    for (int i = 0; i < 60; i++) {
      device = TestFieldDevice.create(t.clock);
      assertThat(get("/api/me", token(Map.of())).status()).as("device " + i).isEqualTo(200);
    }
  }

  @Test
  void fieldRequestInTestSupportBuildsAWorkingRequest() {
    start(b -> b.fieldWorkers(FieldOptions.builder().permissions(FieldPermissions.all("orders:thing:create")).build()), KEY_1);
    AuthTesting auth = AuthTesting.of(t.app);
    FieldPrincipal worker = AuthTesting.fieldWorker("fwu_ABC", "acme", FieldPrincipal.DeviceMode.PERSONAL);
    AuthTesting.FieldRequest req = auth.fieldRequest(worker, device, t.provider::signField, "POST", "/api/things");
    TestResponse r = t.client.post("/api/things").header("Authorization", req.authorization()).header("DPoP", req.dpop()).jsonBody("{}").send();
    assertThat(r.status()).isEqualTo(201);
  }

  @Test
  void fieldRequestIsRefusedOutsideTheTestEnvironment() {
    t = AuthApp.start(dir, b -> {}, Map.of("APP_ENV", "local"));
    Throwable e = catchThrowable(() -> AuthTesting.of(t.app));
    assertThat(e).isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_ENV");
  }

  @Test
  void theFieldTokensVerifierIsProvidedAndReportsTheTokenWithoutAProof() {
    start();
    FieldTokens tokens = t.app.get(FieldTokens.class);
    assertThat(tokens.verify(token(Map.of()))).isInstanceOfSatisfying(FieldTokenResult.Valid.class, v -> {
      assertThat(v.worker().workerId()).isEqualTo("fwu_0TESTWORKER");
      assertThat(v.worker().clientIdentifier()).isEqualTo("acme");
      assertThat(v.worker().deviceMode()).isEqualTo(FieldPrincipal.DeviceMode.PERSONAL);
      assertThat(v.worker().amr()).containsExactly("pin");
      assertThat(v.jkt()).isEqualTo(device.jkt());
    });
    assertThat(tokens.verify(token(Map.of("pcr", true)))).isEqualTo(new FieldTokenResult.Invalid("pcr"));
    t.provider.down(true);
    // keys already read stay usable, so force a restart to see unavailable
  }
}
