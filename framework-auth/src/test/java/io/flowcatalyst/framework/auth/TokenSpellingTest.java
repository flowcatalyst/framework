package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.http.ratelimit.KeyResolvers;
import io.flowcatalyst.framework.http.ratelimit.MemoryRateLimiter;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The token-spelling contract per verifier, with values issued fresh by the test (the vectors hold
 * fixed ones, {@link SpellingConformanceTest}): the value as issued verifies, every re-spelling and
 * every tampered value is refused, and the value as issued still verifies afterwards.
 */
class TokenSpellingTest {

  @TempDir Path dir;

  private static final Set<String> SPELLING_REASONS = Set.of("malformed", "signature");

  @Test
  void anIdTokenSignsInOnlyInTheSpellingItWasIssuedIn() {
    // one sign-in per case: a limiter that lets them all through
    var generous =
        io.flowcatalyst.framework.http.ratelimit.RateLimit.by(MemoryRateLimiter.create("auth", new RateLimitPolicy(1000, Duration.ofMinutes(1), 1000)), KeyResolvers.clientAddress()).build();
    try (AuthApp t = AuthApp.start(dir, b -> b.rateLimit(generous), Map.of())) {
      // the provider signs a new id_token per sign-in; each case is chosen by its index in that
      // token's own list, until the list is done
      List<String> names = new ArrayList<>();
      int[] size = {1};
      for (int i = 0; i < size[0]; i++) {
        int index = i;
        String[] name = new String[1];
        t.provider.idTokenText(
            token -> {
              List<Respellings.Spelling> all = new ArrayList<>(Respellings.of(token, 2));
              all.addAll(Respellings.tampered(token, 2));
              size[0] = all.size();
              name[0] = all.get(index).name();
              return all.get(index).value();
            });
        Browser b = t.browser();
        TestResponse r = b.get(b.local(b.startSignIn("/")));
        assertThat(r.status()).as(name[0]).isEqualTo(400);
        assertThat(r.tree().path("error").asString()).as(name[0]).isEqualTo("INVALID_ID_TOKEN");
        assertThat(r.tree().path("details").path("reason").asString()).as(name[0]).isIn(SPELLING_REASONS);
        assertThat(b.jar).as(name[0]).doesNotContainKey("__Host-fw_session");
        names.add(name[0]);
      }
      // a 2048-bit signature is 342 characters, four spare bits: 15 other last characters
      assertThat(names.stream().filter(n -> n.startsWith("part 2 ends in "))).hasSize(15);
      assertThat(names).contains("part 2 padded with =", "a space before", "CRLF after", "part 2: last bit flipped");
      t.provider.idTokenText(token -> token);
      assertThat(t.browser().signIn("/").status()).as("as issued").isEqualTo(302);
    }
  }

  @Test
  void aBearerTokenVerifiesOnlyInTheSpellingItWasIssuedIn() {
    try (AuthApp t = AuthApp.start(dir)) {
      PlatformTokens tokens = PlatformTokens.createAllowingPlainHttp(t.provider.issuer(), Duration.ofSeconds(5), t.clock);
      for (int round = 0; round < 4; round++) {
        String token = t.provider.accessToken(Map.of());
        assertThat(tokens.verify(token)).isInstanceOf(TokenResult.Valid.class);
        List<Respellings.Spelling> cases = new ArrayList<>(Respellings.of(token, 2));
        cases.addAll(Respellings.tampered(token, 2));
        for (Respellings.Spelling s : cases) {
          TokenResult got = tokens.verify(s.value());
          assertThat(got).as(s.name()).isInstanceOf(TokenResult.Invalid.class);
          assertThat(((TokenResult.Invalid) got).reason()).as(s.name()).isIn(SPELLING_REASONS);
        }
        assertThat(tokens.verify(token)).as("as issued, again").isInstanceOf(TokenResult.Valid.class);
      }
    }
  }

  // ---- DPoP proofs, signed here so the key can be spelled any way --------------------------------

  private static final String HTU = "https://app.example.test/api/orders";
  private static final String TOKEN = "the.access.token";
  private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

  private static byte[] coordinate(BigInteger v) {
    byte[] b = v.toByteArray();
    byte[] out = new byte[32];
    System.arraycopy(b, Math.max(0, b.length - 32), out, Math.max(0, 32 - b.length), Math.min(32, b.length));
    return out;
  }

  /** A proof by {@code key} whose {@code jwk} carries {@code x} and {@code y} as given. */
  private static String proof(KeyPair key, String x, String y) throws GeneralSecurityException {
    return proof(key, x, y, 0, 0);
  }

  /** The same with spaces after the JSON of the header and of the claims (they change the lengths, so the parts take every residue). */
  private static String proof(KeyPair key, String x, String y, int headerSpaces, int claimSpaces) throws GeneralSecurityException {
    String header =
        "{\"typ\":\"dpop+jwt\",\"alg\":\"ES256\",\"jwk\":{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"" + x + "\",\"y\":\"" + y + "\"}}" + " ".repeat(headerSpaces);
    String claims =
        "{\"htm\":\"POST\",\"htu\":\"" + HTU + "\",\"iat\":" + NOW.getEpochSecond() + ",\"jti\":\"j-1\",\"ath\":\"" + FieldProofs.athOf(TOKEN) + "\"}" + " ".repeat(claimSpaces);
    String signed = Base64Url.encode(header.getBytes(StandardCharsets.UTF_8)) + "." + Base64Url.encode(claims.getBytes(StandardCharsets.UTF_8));
    return signed + "." + es256(key, signed);
  }

  private static String es256(KeyPair key, String signed) throws GeneralSecurityException {
    Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
    s.initSign(key.getPrivate());
    s.update(signed.getBytes(StandardCharsets.US_ASCII));
    return Base64Url.encode(s.sign());
  }

  private static FieldProofs.Checked check(String proof, String jkt) {
    return FieldProofs.verify(proof, TOKEN, jkt, "POST", HTU, NOW, Duration.ofMinutes(5));
  }

  @Test
  void aProofAndItsKeyVerifyOnlyInTheSpellingTheyWereIssuedIn() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    for (int round = 0; round < 4; round++) {
      KeyPair key = generator.generateKeyPair();
      ECPublicKey pub = (ECPublicKey) key.getPublic();
      byte[] xb = coordinate(pub.getW().getAffineX());
      byte[] yb = coordinate(pub.getW().getAffineY());
      String x = Base64Url.encode(xb);
      String y = Base64Url.encode(yb);
      String jkt = FieldProofs.thumbprint(xb, yb);
      String issued = proof(key, x, y);
      assertThat(check(issued, jkt)).isInstanceOf(FieldProofs.Checked.Ok.class);

      List<Respellings.Spelling> cases = new ArrayList<>(Respellings.of(issued, 2));
      cases.addAll(Respellings.tampered(issued, 2));
      for (Respellings.Spelling s : cases) {
        assertThat(check(s.value(), jkt)).as(s.name()).isEqualTo(new FieldProofs.Checked.Refused("invalid_proof"));
      }
      // the key's coordinates re-spelled, in a proof the device itself signed: the thumbprint is the
      // same (it is taken over the decoded bytes), so only the spelling rule refuses it
      List<String[]> keys = new ArrayList<>();
      for (String other : Respellings.lastCharacterSpellings(x)) {
        keys.add(new String[] {"x ends in " + other.charAt(other.length() - 1), other, y});
      }
      for (String other : Respellings.lastCharacterSpellings(y)) {
        keys.add(new String[] {"y ends in " + other.charAt(other.length() - 1), x, other});
      }
      keys.add(new String[] {"x padded", x + "=", y});
      keys.add(new String[] {"y padded", x, y + "="});
      keys.add(new String[] {"y with a line feed", x, y.substring(0, 20) + "\n" + y.substring(20)});
      if (x.contains("-") || x.contains("_")) {
        keys.add(new String[] {"x in the standard alphabet", x.replace('-', '+').replace('_', '/'), y});
      }
      assertThat(keys).hasSizeGreaterThanOrEqualTo(3 + 3 + 3);
      for (String[] k : keys) {
        assertThat(check(proof(key, k[1], k[2]), jkt)).as(k[0]).isEqualTo(new FieldProofs.Checked.Refused("invalid_proof"));
      }
      assertThat(check(issued, jkt)).as("as issued, again").isInstanceOf(FieldProofs.Checked.Ok.class);
    }
  }

  @Test
  void aProofWhoseHeaderOrClaimsAreRespelledAndSignedAgainIsRefused() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair key = generator.generateKeyPair();
    ECPublicKey pub = (ECPublicKey) key.getPublic();
    byte[] xb = coordinate(pub.getW().getAffineX());
    byte[] yb = coordinate(pub.getW().getAffineY());
    String x = Base64Url.encode(xb);
    String y = Base64Url.encode(yb);
    String jkt = FieldProofs.thumbprint(xb, yb);
    int checked = 0;
    for (int spaces = 0; spaces < 3; spaces++) {
      for (int which = 0; which < 2; which++) {
        String issued = proof(key, x, y, which == 0 ? spaces : 0, which == 1 ? spaces : 0);
        assertThat(check(issued, jkt)).isInstanceOf(FieldProofs.Checked.Ok.class);
        for (Respellings.Spelling s : Respellings.resigned(issued, text -> {
          try {
            return es256(key, text);
          } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
          }
        })) {
          // the signature is valid over the new text, so only the spelling rule can refuse it
          assertThat(check(s.value(), jkt)).as("%s (%d spaces in part %d)", s.name(), spaces, which).isEqualTo(new FieldProofs.Checked.Refused("invalid_proof"));
          checked++;
        }
      }
    }
    assertThat(checked).isGreaterThan(40);
  }

  // ---- values the application issues itself -------------------------------------------------------

  @Test
  void aSealedCookieOpensOnlyInTheSpellingItWasSealedIn() {
    CookieKeys keys = CookieKeys.parse(Secret.of(CookieKeys.generate("k1")));
    Random random = new Random(20261119);
    int[] spare = new int[5];
    for (int length = 0; length <= 30; length++) {
      byte[] plaintext = new byte[length];
      random.nextBytes(plaintext);
      String sealed = keys.seal("session", plaintext);
      spare[Respellings.spareBits(sealed.split("\\.")[2].length())]++;
      assertThat(keys.open("session", sealed)).hasValue(plaintext);
      List<Respellings.Spelling> cases = new ArrayList<>(Respellings.of(sealed, 2));
      cases.addAll(Respellings.tampered(sealed, 2));
      for (Respellings.Spelling s : cases) {
        assertThat(keys.open("session", s.value())).as("%d bytes, %s", length, s.name()).isEmpty();
      }
      assertThat(keys.open("session", sealed)).as("as sealed, again").hasValue(plaintext);
    }
    assertThat(spare[0]).as("no spare bits").isPositive();
    assertThat(spare[2]).as("two spare bits").isPositive();
    assertThat(spare[4]).as("four spare bits").isPositive();
  }

  @Test
  void aNonceIsValidOnlyInTheSpellingItWasIssuedIn() {
    CookieKeys keys = CookieKeys.parse(Secret.of(CookieKeys.generate("k1")));
    for (int minute = 0; minute < 60; minute += 7) {
      FieldNonces nonces = new FieldNonces(keys, InstantSource.fixed(NOW.plusSeconds(60L * minute)));
      String nonce = nonces.issue();
      assertThat(nonces.valid(nonce)).isTrue();
      List<Respellings.Spelling> cases = new ArrayList<>(Respellings.of(nonce, 0));
      cases.addAll(Respellings.tampered(nonce, 0));
      for (Respellings.Spelling s : cases) {
        assertThat(nonces.valid(s.value())).as(s.name()).isFalse();
      }
      assertThat(nonces.valid(nonce)).as("as issued, again").isTrue();
    }
  }
}
