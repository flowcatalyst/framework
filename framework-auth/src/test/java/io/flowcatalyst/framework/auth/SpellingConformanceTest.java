package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.context.ApplicationCode;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The token-spelling contract for the auth module: writes and checks {@code
 * conformance/auth/spelling.json}, and checks {@code conformance/auth/issued-before.json}. Every
 * value as issued verifies; every re-spelling (contract section 3) and every tampered value is
 * refused, each with the answer the vector records.
 */
class SpellingConformanceTest {

  static final String FILE = "auth/spelling.json";
  static final String ISSUER = "https://fc.example.test";
  static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
  static final String KID = "spelling-test-1";

  // A TEST KEY, published on purpose so every implementation verifies the same token: never
  // configure it anywhere real. RS256 is deterministic, so the vector is the same on every run.
  private static final BigInteger N =
      new BigInteger(
          "ee6a54e5586d8fe90cb4de3e44fae7487e56d2294a1900889ffa1309e011ff01f099ec6ce7be6f1eb253f1671c8cda29204db9e83b5d12dccc2a971024d858f5"
              + "485e5b6674c8ab42753ca39c9e6dccb9f4d69ce9aae2d847d68936451750bc088a6d275320fc2d5dfc159830295c0b61373713f21765857a51b163e9c4f7f19a"
              + "189d4cd553491a2d3c8a358a3eb3985cda2e8c85a3422a013c4bc732f59a7b8edf497a39b6767283b5f2edb6080703512c946c95cdcc435d7f692c3c523e93cd"
              + "c5db8127b1e2e47bd9eff739c7ccb6b837bb4c6877c5ef9cf640e0cdf38418f3162e19540059e8c0738a33c505dd406ba8b4527cbb645d58b974c20bf132fc3d",
          16);
  private static final BigInteger D =
      new BigInteger(
          "3a009476d77490365a6906488fef9aa500578ef98c955893e497aa5a14b14c20e4b00ddcd0e47b94343e47d496158e5dc30625dc61d4dae9eca1cfee95882cdc"
              + "3fa6f5059013e1d6613f476eaafc7b7a221eb43e04dcfb513ed852e3ac30700e83deda5a570402e582969e57d938136cab4a33080ab920280a07e60ed164a0da"
              + "2c344473c04c72e07370f29ef4505436e9efbcfed4ead57d8541b3d424301f24680b2dc53fb0f8822cba0292857df67bc4f6106c9137f47ce85cbd1f6e1bdb20"
              + "796ab35d398cddd917f79cbdeed7bfc3c9bfacabbc17d36b23184f257ae5e07e1683f10e708b1b2780fa886eb7be2943e14e05479639245d9c729aef460189e1",
          16);
  private static final BigInteger E = BigInteger.valueOf(65537);

  static RSAPublicKey publicKey() {
    try {
      return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(N, E));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static String accessToken() {
    return accessToken(0, 0);
  }

  /**
   * The issued access token with {@code headerSpaces} and {@code payloadSpaces} spaces after the
   * JSON of its header and payload: harmless to the JSON, and they change the lengths, so the parts
   * take every residue mod 4 (and so every count of spare bits) in the vector.
   */
  static String accessToken(int headerSpaces, int payloadSpaces) {
    String header = "{\"alg\":\"RS256\",\"kid\":\"" + KID + "\",\"typ\":\"JWT\"}" + " ".repeat(headerSpaces);
    String claims =
        "{\"iss\":\""
            + ISSUER
            + "\",\"aud\":\""
            + ISSUER
            + "\",\"sub\":\"prn_0SPELLINGTEST\",\"type\":\"SERVICE\",\"token_use\":\"api\",\"scope\":\"orders:read\",\"iat\":"
            + NOW.getEpochSecond()
            + ",\"exp\":"
            + NOW.plusSeconds(3600).getEpochSecond()
            + "}"
            + " ".repeat(payloadSpaces);
    String signed = Base64Url.encode(header.getBytes(StandardCharsets.UTF_8)) + "." + Base64Url.encode(claims.getBytes(StandardCharsets.UTF_8));
    return signed + "." + signRs256(signed);
  }

  /** The RS256 signature part over the text, with the test key. */
  static String signRs256(String signed) {
    try {
      PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateKeySpec(N, D));
      Signature s = Signature.getInstance("SHA256withRSA");
      s.initSign(key);
      s.update(signed.getBytes(StandardCharsets.US_ASCII));
      return Base64Url.encode(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Header and payload re-spelled and signed again, over tokens whose parts take every residue mod 4:
   * a lenient verifier would accept each of these (the signature is valid over the new text).
   */
  static List<Respellings.Spelling> resignedAccessTokens() {
    List<Respellings.Spelling> out = new ArrayList<>();
    List<Integer>[] counts = new List[] {new ArrayList<Integer>(), new ArrayList<Integer>()};
    for (int spaces = 0; spaces < 3; spaces++) {
      for (int which = 0; which < 2; which++) {
        String token = accessToken(which == 0 ? spaces : 0, which == 1 ? spaces : 0);
        assertThat(verifyAccessToken(token)).as("the base of the re-signed cases").isEqualTo("valid");
        String part = (which == 0 ? "header" : "payload") + " with " + spaces + " trailing spaces";
        int lastCharacters = 0;
        for (Respellings.Spelling s : Respellings.resigned(token, SpellingConformanceTest::signRs256)) {
          if (s.name().startsWith("part " + which + " ")) {
            out.add(new Respellings.Spelling(part + ": " + s.name(), s.value()));
            lastCharacters += s.name().startsWith("part " + which + " ends in ") ? 1 : 0;
          }
        }
        counts[which].add(lastCharacters);
      }
    }
    // each part took every count of spare bits: none (0 other last characters), two (3), four (15)
    assertThat(counts[0]).containsExactlyInAnyOrder(0, 3, 15);
    assertThat(counts[1]).containsExactlyInAnyOrder(0, 3, 15);
    return out;
  }

  /** The access-token answer: "valid" or the refusal reason (contract 5.5 steps 1 to 4, 5.6). */
  static String verifyAccessToken(String token) {
    RSAPublicKey key = publicKey();
    try {
      return switch (Jwt.verify(token, kid -> kid.equals(KID) ? Optional.of(key) : Optional.empty())) {
        case Jwt.Checked.Refused r -> r.reason();
        case Jwt.Checked.Ok ok -> Claims.accessToken(ok.claims(), ISSUER, NOW).orElse("valid");
      };
    } catch (PlatformUnavailableException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- the field fixtures' valid case (platform-issued token and device-signed proof) ----------

  record FieldCase(String issuer, String app, String token, String proof, String method, URI url, Instant now, Jwt.KeySource keys) {}

  static FieldCase fieldCase() {
    JsonNode cases = JsonVectors.mapper().readTree(Conformance.read("field/cases.json"));
    JsonNode jwks = JsonVectors.mapper().readTree(Conformance.read("field/jwks.json"));
    java.util.Map<String, RSAPublicKey> keys = new java.util.HashMap<>();
    for (JsonNode jwk : jwks.path("keys")) {
      keys.put(jwk.path("kid").asString(), Platform.rsaKey(jwk));
    }
    for (JsonNode c : cases.path("cases")) {
      if (c.path("name").asString().equals("valid")) {
        return new FieldCase(
            cases.path("issuer").asString(),
            cases.path("appCode").asString(),
            c.path("accessToken").asString(),
            c.path("dpopProof").asString(),
            c.path("method").asString(),
            URI.create(c.path("url").asString()),
            Instant.parse(c.path("now").asString()),
            kid -> Optional.ofNullable(keys.get(kid)));
      }
    }
    throw new IllegalStateException("no valid case in field/cases.json");
  }

  /** The field answer: "valid" or the refusal reason, with a fresh replay cache and the vectors' nonce and window rules (15.9). */
  static String verifyField(FieldCase f, String token, String proof) {
    MutableClock clock = new MutableClock(f.now());
    FieldTokens tokens = new FieldTokens(f.keys(), f.issuer(), new ApplicationCode(f.app()), clock);
    String base = f.url().getScheme() + "://" + f.url().getRawAuthority();
    FieldVerifier verifier = new FieldVerifier(tokens, base, Duration.ofMinutes(5), null, FieldReplayCache.memory(clock), clock);
    return switch (verifier.verify(token, List.of(proof), f.method(), f.url().getRawPath())) {
      case FieldVerifier.Outcome.Verified v -> "valid";
      case FieldVerifier.Outcome.Refused r -> r.reason();
      case FieldVerifier.Outcome.PinChangeRequired p -> "pcr";
      case FieldVerifier.Outcome.NonceRequired n -> "nonce_required";
      case FieldVerifier.Outcome.Unavailable u -> "unavailable";
    };
  }

  /** The field token alone (the proof is a separate check): "valid" or the refusal reason. */
  static String verifyFieldToken(FieldCase f, String token) {
    FieldTokens tokens = new FieldTokens(f.keys(), f.issuer(), new ApplicationCode(f.app()), new MutableClock(f.now()));
    return switch (tokens.verify(token)) {
      case FieldTokenResult.Valid v -> "valid";
      case FieldTokenResult.Invalid i -> i.reason();
      case FieldTokenResult.Unavailable u -> "unavailable";
    };
  }

  // ---- the fixtures issued before the rule ------------------------------------------------------

  static JsonNode issuedBefore() {
    return JsonVectors.mapper().readTree(Conformance.read("auth/issued-before.json"));
  }

  static CookieKeys cookieKeys(JsonNode before) {
    return CookieKeys.parse(Secret.of(before.path("cookieKeys").asString()));
  }

  static String nonceAnswer(CookieKeys keys, Instant now, String nonce) {
    return new FieldNonces(keys, InstantSource.fixed(now)).valid(nonce) ? "valid" : "invalid";
  }

  static String cookieAnswer(CookieKeys keys, String kind, String value, String plaintext) {
    Optional<byte[]> opened = keys.open(kind, value);
    if (opened.isEmpty()) {
      return "refused";
    }
    assertThat(new String(opened.get(), StandardCharsets.UTF_8)).as(value).isEqualTo(plaintext);
    return "opens";
  }

  // ---- the document -----------------------------------------------------------------------------

  /** One value: as issued (must give {@code valid}), then each changed spelling with its answer, none of them {@code valid}. */
  private static void cases(ObjectNode into, String issued, String valid, int target, Function<String, String> verify) {
    cases(into, issued, valid, target, verify, List.of());
  }

  private static void cases(
      ObjectNode into, String issued, String valid, int target, Function<String, String> verify, List<Respellings.Spelling> resigned) {
    into.put("issued", issued);
    String answer = verify.apply(issued);
    assertThat(answer).as("as issued: %s", issued).isEqualTo(valid);
    into.put("expect", answer);
    ArrayNode respelled = into.putArray("respelled");
    for (Respellings.Spelling s : Respellings.of(issued, target)) {
      String got = verify.apply(s.value());
      assertThat(got).as("re-spelled, %s", s.name()).isNotEqualTo(valid);
      respelled.addObject().put("name", s.name()).put("value", s.value()).put("expect", got);
    }
    ArrayNode tampered = into.putArray("tampered");
    for (Respellings.Spelling s : Respellings.tampered(issued, target)) {
      String got = verify.apply(s.value());
      assertThat(got).as("tampered, %s", s.name()).isNotEqualTo(valid);
      tampered.addObject().put("name", s.name()).put("value", s.value()).put("expect", got);
    }
    if (!resigned.isEmpty()) {
      ArrayNode signedAgain = into.putArray("resigned");
      for (Respellings.Spelling s : resigned) {
        String got = verify.apply(s.value());
        assertThat(got).as("re-spelled and signed again, %s", s.name()).isNotEqualTo(valid);
        signedAgain.addObject().put("name", s.name()).put("value", s.value()).put("expect", got);
      }
    }
    assertThat(verify.apply(issued)).as("as issued, again").isEqualTo(valid);
  }

  private static ArrayNode segments(ObjectNode doc) {
    ArrayNode out = doc.putArray("segments");
    byte[][] samples = {{}, {(byte) 0xff}, {(byte) 0xff, (byte) 0xff}, {(byte) 0xff, (byte) 0xff, (byte) 0xff}, {0}, {0, 0}, {(byte) 0xfb, (byte) 0xef}};
    for (byte[] b : samples) {
      String canonical = Base64Url.encode(b);
      out.addObject().put("text", canonical).put("canonical", true).put("hex", HexFormat.of().formatHex(b));
      if (canonical.isEmpty()) {
        continue;
      }
      for (Respellings.Spelling s : Respellings.of(canonical, 0)) {
        if (s.name().endsWith("before") || s.name().endsWith("after")) {
          continue; // the part's own cases are enough here; the values below carry the rest
        }
        assertThat(Base64Url.isCanonical(s.value())).as(s.name()).isFalse();
        out.addObject().put("text", s.value()).put("canonical", false).put("why", s.name().replace("the value", canonical));
      }
    }
    for (String text : List.of("A", "AAAAA", "A.A", "*A", "AA AA", "AA\tA")) {
      assertThat(Base64Url.isCanonical(text)).as(text).isFalse();
      out.addObject().put("text", text).put("canonical", false).put("why", "not base64url at all");
    }
    return out;
  }

  ObjectNode document() {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "The token-spelling contract (docs/contracts/token-spelling.md) for the auth module. 'segments': strings that are, or are not,"
            + " canonical base64url (section 2), with the bytes of each canonical one in hex. Then one entry per kind of value the module"
            + " verifies, each with its setup, the value as 'issued' (whose answer, 'expect', is the valid answer), every re-spelling of it"
            + " ('respelled': section 3, the signature, nonce or sealed part re-spelled every way, the other parts padded, whitespace and line"
            + " breaks around the value), changes that certainly alter it ('tampered') and, where the test can sign, a header or payload"
            + " re-spelled and signed again ('resigned': the signature is valid over the new text, so a lenient decoder would accept it),"
            + " each with the answer it must get ('resigned' is only in accessToken: the platform's and a device's keys are not ours, so"
            + " a field token and a proof are only ever re-spelled in their signature; the per-verifier tests sign proofs of their own"
            + " and re-spell them everywhere). accessToken: the"
            + " platform access-token checks of sessions contract 5.5 steps 1 to 4 and 5.6, with the key in 'jwks' (a TEST KEY, published"
            + " on purpose), the issuer and 'now'; the answer is 'valid' or the refusal reason. field: the valid case of"
            + " conformance/field/cases.json (keys in conformance/field/jwks.json), the token re-spelled ('token': with the valid proof, so a lenient token decoder is still refused by the proof's ath, which binds to the token's exact text; 'tokenOnly': the token checked alone, as the access token is) and then the proof, checked as"
            + " that file is (15.9: no application-nonce check, a five-minute proof window, a fresh replay cache per check); the answer is"
            + " 'valid' or the reason. fieldNonces: application nonces (15.4) under 'cookieKeys', each checked at its 'now': 'valid' or"
            + " 'invalid'. sealedCookies: values sealed by the cookie driver (6.5) under 'cookieKeys', opened for their 'kind': 'opens'"
            + " (to 'plaintext') or 'refused'. The nonces and sealed values are those of conformance/auth/issued-before.json, issued by"
            + " the Go and the Java code before the rule. Written by framework-auth SpellingConformanceTest.");
    doc.put(
        "howToRun",
        "For 'segments', the implementation's canonical decoder accepts exactly the canonical ones, giving 'hex'. For each kind, build"
            + " its verifier from the setup, then check 'issued' and every case of 'respelled', 'tampered' and 'resigned' and compare the answer with"
            + " 'expect'. Values are passed to the verifier exactly as written (no trimming): the HTTP layer's own syntax is not part of them.");
    segments(doc);

    ObjectNode access = doc.putObject("accessToken");
    access.put("issuer", ISSUER);
    access.put("now", NOW.toString());
    ObjectNode jwk = access.putObject("jwks").putArray("keys").addObject();
    jwk.put("kty", "RSA").put("kid", KID).put("use", "sig").put("alg", "RS256");
    jwk.put("n", Base64Url.encode(unsigned(N))).put("e", Base64Url.encode(unsigned(E)));
    cases(access, accessToken(), "valid", 2, SpellingConformanceTest::verifyAccessToken, resignedAccessTokens());

    FieldCase f = fieldCase();
    ObjectNode field = doc.putObject("field");
    field.put("fixture", "field/cases.json, case 'valid'");
    field.put("issuer", f.issuer()).put("appCode", f.app()).put("method", f.method()).put("url", f.url().toString()).put("now", f.now().toString());
    ObjectNode token = field.putObject("token");
    token.put("proof", f.proof());
    cases(token, f.token(), "valid", 2, t -> verifyField(f, t, f.proof()));
    // the token alone: the proof binds to the token's exact text (ath), so with it the answer to a re-spelled token is
    // never "valid" even for a lenient decoder; checked alone, the answer shows whether the token's decoding is strict
    ObjectNode tokenOnly = field.putObject("tokenOnly");
    cases(tokenOnly, f.token(), "valid", 2, t -> verifyFieldToken(f, t));
    ObjectNode proof = field.putObject("proof");
    proof.put("token", f.token());
    cases(proof, f.proof(), "valid", 2, p -> verifyField(f, f.token(), p));

    JsonNode before = issuedBefore();
    CookieKeys keys = cookieKeys(before);
    ObjectNode nonces = doc.putObject("fieldNonces");
    nonces.put("cookieKeys", before.path("cookieKeys").asString());
    ArrayNode nonceCases = nonces.putArray("cases");
    for (JsonNode n : before.path("fieldNonces")) {
      Instant at = Instant.parse(n.path("now").asString());
      ObjectNode c = nonceCases.addObject();
      c.put("now", at.toString());
      cases(c, n.path("nonce").asString(), "valid", 0, v -> nonceAnswer(keys, at, v));
    }
    ObjectNode cookies = doc.putObject("sealedCookies");
    cookies.put("cookieKeys", before.path("cookieKeys").asString());
    ArrayNode cookieCases = cookies.putArray("cases");
    for (JsonNode s : before.path("sealedCookies")) {
      String kind = s.path("kind").asString();
      String plaintext = s.path("plaintext").asString();
      ObjectNode c = cookieCases.addObject();
      c.put("issuedBy", s.path("issuedBy").asString()).put("kind", kind).put("plaintext", plaintext);
      cases(c, s.path("value").asString(), "opens", 2, v -> cookieAnswer(keys, kind, v, plaintext));
    }
    return doc;
  }

  @Test
  void theVectorMatches() {
    JsonVectors.check(FILE, document());
  }

  @Test
  void valuesIssuedBeforeTheRuleStillVerify() {
    JsonNode before = issuedBefore();
    CookieKeys keys = cookieKeys(before);
    int sealed = 0;
    for (JsonNode s : before.path("sealedCookies")) {
      assertThat(cookieAnswer(keys, s.path("kind").asString(), s.path("value").asString(), s.path("plaintext").asString()))
          .as("%s sealed by %s", s.path("kind").asString(), s.path("issuedBy").asString())
          .isEqualTo("opens");
      sealed++;
    }
    int nonces = 0;
    for (JsonNode n : before.path("fieldNonces")) {
      assertThat(nonceAnswer(keys, Instant.parse(n.path("now").asString()), n.path("nonce").asString())).as(n.toString()).isEqualTo("valid");
      nonces++;
    }
    assertThat(sealed).isEqualTo(10);
    assertThat(nonces).isEqualTo(2);
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] b = value.toByteArray();
    return b.length > 1 && b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
  }
}
