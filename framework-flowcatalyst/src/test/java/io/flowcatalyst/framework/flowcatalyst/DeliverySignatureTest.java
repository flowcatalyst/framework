package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.flowcatalyst.DeliverySignature.Failure;
import io.flowcatalyst.framework.flowcatalyst.DeliverySignature.Invalid;
import io.flowcatalyst.framework.flowcatalyst.DeliverySignature.Valid;
import io.flowcatalyst.framework.flowcatalyst.DeliverySignature.Verdict;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The signature rules shared with the Go, TypeScript and Java SDK verifiers. Each rule has a test
 * that fails when the rule is broken (see the mutation notes in the commit).
 */
class DeliverySignatureTest {

  static final String SECRET = "test-signing-secret";
  static final Instant NOW = Instant.parse("2026-10-09T12:00:00.000Z");
  static final String BODY = "{\"jobCode\":\"orders:ops:cleanup:run\"}";

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static Verdict check(String body, @Nullable String sig, @Nullable String ts) {
    return check(body, sig, ts, SECRET, null, null, NOW);
  }

  private static Verdict check(
      String body,
      @Nullable String sig,
      @Nullable String ts,
      String secret,
      @Nullable String bearer,
      @Nullable String authorization,
      Instant now) {
    return DeliverySignature.check(() -> bytes(body), sig, ts, secret, bearer, authorization, now);
  }

  private static String sign(String ts, String body) {
    return DeliverySignature.hmacHex(SECRET, ts, bytes(body));
  }

  private static Verdict at(String ts) {
    return check(BODY, sign(ts, BODY), ts);
  }

  private static Invalid invalid(Failure failure) {
    return new Invalid(failure);
  }

  // ---- the shared vectors ------------------------------------------------------------------

  @Test
  void theVectorSharedWithTheOtherSdks() {
    // Java SDK WebhookSignatureTest.knownVectorMatchesOtherSdks; also computed with openssl.
    assertThat(DeliverySignature.hmacHex("secret", "2026-08-07T09:32:12.123Z", bytes("hello")))
        .isEqualTo("a7d62aade7a79e88792f84e035b72788a3c78a30f46ad265ec8d6fb12a4e9f91");
  }

  @Test
  void theRouterTestVectorComputedIndependently() {
    // Input of the Go verifier's TestVerifyMatchesRouterSigner, expected value from
    // `printf '%s' "$ts$body" | openssl dgst -sha256 -hmac "$secret"`.
    assertThat(
            DeliverySignature.hmacHex(
                "test-secret-do-not-use-in-prod",
                "2026-05-24T08:30:00.123Z",
                bytes("{\"messageId\":\"msg_TEST123456\"}")))
        .isEqualTo("0278cfb47ccbf20a2356584cca6e076cbade089ccc961dbe553f0e768cbf9059");
  }

  @Test
  void theSignedMessageIsTheRawTimestampThenTheRawBody() {
    String ts = "2026-10-09T12:00:00.000Z";
    // Neither the body alone, nor the body then the timestamp, nor another spelling of the time.
    assertThat(check(BODY, DeliverySignature.hmacHex(SECRET, "", bytes(BODY)), ts))
        .isEqualTo(invalid(Failure.INVALID_SIGNATURE));
    assertThat(check(BODY, DeliverySignature.hmacHex(SECRET, BODY, bytes(ts)), ts))
        .isEqualTo(invalid(Failure.INVALID_SIGNATURE));
    String sameInstant = "1791547200";
    assertThat(check(BODY, sign(sameInstant, BODY), ts)).isEqualTo(invalid(Failure.INVALID_SIGNATURE));
    assertThat(check(BODY, sign(ts, BODY), ts)).isEqualTo(new Valid());
  }

  // ---- accepting ---------------------------------------------------------------------------

  @Test
  void aValidSignatureIsAccepted() {
    assertThat(at("2026-10-09T12:00:00.000Z")).isEqualTo(new Valid());
  }

  @Test
  void theSignatureIsComparedCaseInsensitively() {
    String ts = "2026-10-09T12:00:00.000Z";
    assertThat(check(BODY, sign(ts, BODY).toUpperCase(Locale.ROOT), ts)).isEqualTo(new Valid());
  }

  @Test
  void unixSecondsAreAcceptedAndTheRawStringIsWhatIsSigned() {
    String ts = String.valueOf(NOW.getEpochSecond());
    assertThat(at(ts)).isEqualTo(new Valid());
  }

  // ---- the window: exactly 300 s past and 60 s future, in whole seconds --------------------

  @Test
  void exactly300SecondsOldIsAccepted() {
    assertThat(at("2026-10-09T11:55:00.000Z")).isEqualTo(new Valid());
  }

  @Test
  void oneMillisecondMoreThan300SecondsOldIsRejected() {
    assertThat(at("2026-10-09T11:54:59.999Z")).isEqualTo(invalid(Failure.TIMESTAMP_EXPIRED));
    assertThat(at(String.valueOf(NOW.getEpochSecond() - 301))).isEqualTo(invalid(Failure.TIMESTAMP_EXPIRED));
  }

  @Test
  void exactly60SecondsAheadIsAccepted() {
    assertThat(at("2026-10-09T12:01:00.999Z")).isEqualTo(new Valid());
    assertThat(at(String.valueOf(NOW.getEpochSecond() + 60))).isEqualTo(new Valid());
  }

  @Test
  void sixtyOneSecondsAheadIsRejected() {
    assertThat(at("2026-10-09T12:01:01.000Z")).isEqualTo(invalid(Failure.TIMESTAMP_IN_FUTURE));
  }

  @Test
  void aReplayAnHourLaterIsRejectedEvenWithAValidSignature() {
    String ts = "2026-10-09T12:00:00.000Z";
    String sig = sign(ts, BODY);
    assertThat(check(BODY, sig, ts, SECRET, null, null, NOW.plusSeconds(3600)))
        .isEqualTo(invalid(Failure.TIMESTAMP_EXPIRED));
  }

  // ---- refusing ----------------------------------------------------------------------------

  @Test
  void anEmptySecretFailsClosedWhateverIsSent() {
    String ts = "2026-10-09T12:00:00.000Z";
    for (String sig : new String[] {sign(ts, BODY), "0".repeat(64), ""}) {
      assertThat(check(BODY, sig, ts, "", null, null, NOW)).isEqualTo(invalid(Failure.MISSING_SECRET));
    }
  }

  @Test
  void missingHeadersAreRejected() {
    String ts = "2026-10-09T12:00:00.000Z";
    String sig = sign(ts, BODY);
    assertThat(check(BODY, null, ts)).isEqualTo(invalid(Failure.MISSING_SIGNATURE));
    assertThat(check(BODY, "", ts)).isEqualTo(invalid(Failure.MISSING_SIGNATURE));
    assertThat(check(BODY, sig, null)).isEqualTo(invalid(Failure.MISSING_TIMESTAMP));
    assertThat(check(BODY, sig, "")).isEqualTo(invalid(Failure.MISSING_TIMESTAMP));
  }

  @Test
  void unparseableTimestampsAreRejected() {
    for (String ts :
        new String[] {
          "not-a-time",
          "2026-10-09 12:00:00",
          "١٧٩١٥٤٧٢٠٠", // Arabic-Indic digits: Long.parseLong would accept them
          "1".repeat(19), // would overflow a long
          "2026-10-09T12:00:00.000Z" + " ".repeat(60), // over-long
          "-1791547200"
        }) {
      assertThat(check(BODY, sign(ts, BODY), ts)).as(ts).isEqualTo(invalid(Failure.INVALID_TIMESTAMP));
    }
  }

  @Test
  void anyChangeToTheSignatureIsRejected() {
    String ts = "2026-10-09T12:00:00.000Z";
    String sig = sign(ts, BODY);
    String firstFlipped = (sig.charAt(0) == 'a' ? "b" : "a") + sig.substring(1);
    String lastFlipped = sig.substring(0, 63) + (sig.charAt(63) == 'a' ? "b" : "a");
    for (String bad : new String[] {firstFlipped, lastFlipped, sig.substring(1), sig + "0", "0".repeat(64)}) {
      assertThat(check(BODY, bad, ts)).as(bad).isEqualTo(invalid(Failure.INVALID_SIGNATURE));
    }
  }

  @Test
  void anyChangeToTheBodyOrTheSecretIsRejected() {
    String ts = "2026-10-09T12:00:00.000Z";
    String sig = sign(ts, BODY);
    assertThat(check(BODY + "\n", sig, ts)).isEqualTo(invalid(Failure.INVALID_SIGNATURE));
    assertThat(check(BODY.replace(":", " : "), sig, ts)).isEqualTo(invalid(Failure.INVALID_SIGNATURE));
    assertThat(check(BODY, sig, ts, SECRET + "x", null, null, NOW)).isEqualTo(invalid(Failure.INVALID_SIGNATURE));
  }

  @Test
  void theBodyIsNotReadUntilTheHeadersPass() {
    AtomicInteger reads = new AtomicInteger();
    DeliverySignature.Body body =
        () -> {
          reads.incrementAndGet();
          return bytes(BODY);
        };
    String ts = "2026-10-09T12:00:00.000Z";
    String sig = sign(ts, BODY);
    DeliverySignature.check(body, null, ts, SECRET, null, null, NOW);
    DeliverySignature.check(body, sig, null, SECRET, null, null, NOW);
    DeliverySignature.check(body, sig, "2020-01-01T00:00:00.000Z", SECRET, null, null, NOW);
    DeliverySignature.check(body, sig, ts, "", null, null, NOW);
    DeliverySignature.check(body, "abc", ts, SECRET, null, null, NOW);
    assertThat(reads).hasValue(0);
    assertThat(DeliverySignature.check(body, sig, ts, SECRET, null, null, NOW)).isEqualTo(new Valid());
    assertThat(reads).hasValue(1);
  }

  // ---- the bearer gate ---------------------------------------------------------------------

  @Test
  void theBearerGateIsAndedWithTheSignature() {
    String ts = "2026-10-09T12:00:00.000Z";
    String sig = sign(ts, BODY);
    assertThat(check(BODY, sig, ts, SECRET, "tok-123", "Bearer tok-123", NOW)).isEqualTo(new Valid());
    assertThat(check(BODY, sig, ts, SECRET, "tok-123", null, NOW)).isEqualTo(invalid(Failure.MISSING_BEARER));
    assertThat(check(BODY, sig, ts, SECRET, "tok-123", "bearer tok-123", NOW))
        .isEqualTo(invalid(Failure.MISSING_BEARER));
    assertThat(check(BODY, sig, ts, SECRET, "tok-123", "Bearer tok-124", NOW))
        .isEqualTo(invalid(Failure.INVALID_BEARER));
    assertThat(check(BODY, sig, ts, SECRET, "tok-123", "Bearer tok-1234", NOW))
        .isEqualTo(invalid(Failure.INVALID_BEARER));
    assertThat(check(BODY, sig, ts, SECRET, "tok-123", "Bearer ", NOW)).isEqualTo(invalid(Failure.INVALID_BEARER));
    // A right bearer never stands in for a wrong signature.
    assertThat(check(BODY, "0".repeat(64), ts, SECRET, "tok-123", "Bearer tok-123", NOW))
        .isEqualTo(invalid(Failure.INVALID_SIGNATURE));
  }

  @Test
  void theBearerGateAloneIsOffWithoutAToken() {
    assertThat(DeliverySignature.checkBearer(null, null)).isEqualTo(new Valid());
    assertThat(DeliverySignature.checkBearer("", "Bearer x")).isEqualTo(new Valid());
    assertThat(DeliverySignature.checkBearer("t", "Bearer t")).isEqualTo(new Valid());
    assertThat(DeliverySignature.checkBearer("t", "Bearer u")).isEqualTo(invalid(Failure.INVALID_BEARER));
  }
}
