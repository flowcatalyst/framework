package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.RawRequest;
import io.flowcatalyst.framework.http.Response;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks {@code conformance/receivers/signature.json} (flowcatalyst contract section 4,
 * signature check): the receiver gate's answer to a delivery's headers and body. Java writes it;
 * Go compares.
 */
class SignatureConformanceTest {

  static final String FILE = "receivers/signature.json";
  static final String SECRET = "whsec-test-only";
  static final String BEARER = "tok-123";
  static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
  static final String BODY = "{\"n\":1}";

  /** One delivery to the gate. Headers are a list so a header can be sent twice. */
  record Case(
      String name,
      String secret,
      String bearer,
      boolean requireSignature,
      List<String[]> headers,
      String body) {}

  static String iso(long offsetSeconds) {
    return DateTimeFormatter.ISO_INSTANT.format(NOW.plusSeconds(offsetSeconds)).replace("Z", ".000Z");
  }

  static String sign(String secret, String ts, String body) {
    return DeliverySignature.hmacHex(secret, ts, body.getBytes(StandardCharsets.UTF_8));
  }

  /** A fluent builder for a case. */
  static final class B {
    final String name;
    String secret = SECRET;
    String bearer = null;
    boolean require = true;
    String body = BODY;
    final List<String[]> headers = new ArrayList<>();

    B(String name) {
      this.name = name;
    }

    B secret(String s) {
      secret = s;
      return this;
    }

    B bearer(String s) {
      bearer = s;
      return this;
    }

    B require(boolean r) {
      require = r;
      return this;
    }

    B body(String b) {
      body = b;
      return this;
    }

    B h(String n, String v) {
      headers.add(new String[] {n, v});
      return this;
    }

    /** Signature and timestamp, signed with the case's own secret over the case's body. */
    B signed(String ts) {
      return h("X-FlowCatalyst-Signature", sign(secret == null ? "" : secret, ts, body))
          .h("X-FlowCatalyst-Timestamp", ts);
    }

    B signedWith(String signingSecret, String ts) {
      return h("X-FlowCatalyst-Signature", sign(signingSecret, ts, body)).h("X-FlowCatalyst-Timestamp", ts);
    }

    Case build() {
      return new Case(name, secret, bearer, require, headers, body);
    }
  }

  static List<Case> cases() {
    List<Case> c = new ArrayList<>();
    String ts = iso(0);
    // 1 valid
    c.add(new B("valid signature").signed(ts).build());
    c.add(new B("valid signature, unix seconds timestamp").signed(String.valueOf(NOW.getEpochSecond())).build());
    // 2 window
    c.add(new B("300 s old is accepted").signed(iso(-300)).build());
    c.add(new B("301 s old is refused").signed(iso(-301)).build());
    c.add(new B("60 s in the future is accepted").signed(iso(60)).build());
    c.add(new B("61 s in the future is refused").signed(iso(61)).build());
    c.add(
        new B("300.999 s old is accepted (whole seconds)")
            .signed("2026-10-09T11:55:00.999Z")
            .build());
    // 3 timestamp shape
    c.add(new B("timestamp over 64 characters").signed("2026-10-09T12:00:00.000Z" + " ".repeat(60)).build());
    c.add(new B("non-numeric timestamp").signed("not-a-time").build());
    c.add(new B("empty timestamp").h("X-FlowCatalyst-Signature", sign(SECRET, "", BODY)).h("X-FlowCatalyst-Timestamp", "").build());
    c.add(new B("negative unix timestamp").signed("-1791547200").build());
    c.add(new B("non-ASCII digit timestamp").signed("١٧٩١٥٤٧٢٠٠").build());
    // 4 signature shape
    c.add(new B("signature 63 characters").h("X-FlowCatalyst-Signature", sign(SECRET, ts, BODY).substring(1)).h("X-FlowCatalyst-Timestamp", ts).build());
    c.add(new B("signature 65 characters").h("X-FlowCatalyst-Signature", sign(SECRET, ts, BODY) + "0").h("X-FlowCatalyst-Timestamp", ts).build());
    c.add(
        new B("upper-case hex signature is accepted")
            .h("X-FlowCatalyst-Signature", sign(SECRET, ts, BODY).toUpperCase(java.util.Locale.ROOT))
            .h("X-FlowCatalyst-Timestamp", ts)
            .build());
    c.add(new B("non-hex signature of length 64").h("X-FlowCatalyst-Signature", "z".repeat(64)).h("X-FlowCatalyst-Timestamp", ts).build());
    // 5 secret and body
    c.add(new B("signed with another secret").signedWith("another-secret", ts).build());
    // Java's HMAC refuses an empty key, so the 'matching empty-key MAC' cannot be generated here; a
    // verifier that can compute one (Go) must still refuse.
    c.add(new B("empty secret fails closed").secret(null).h("X-FlowCatalyst-Signature", "0".repeat(64)).h("X-FlowCatalyst-Timestamp", ts).build());
    c.add(new B("empty string secret fails closed").secret("").h("X-FlowCatalyst-Signature", "0".repeat(64)).h("X-FlowCatalyst-Timestamp", ts).build());
    c.add(new B("body altered by one byte").signed(ts).body("{\"n\":2}").build());
    c.add(new B("signature over body then timestamp").h("X-FlowCatalyst-Signature", sign(SECRET, BODY, ts)).h("X-FlowCatalyst-Timestamp", ts).build());
    // 6 duplicates
    String sig = sign(SECRET, ts, BODY);
    c.add(new B("signature header sent twice").h("X-FlowCatalyst-Signature", sig).h("X-FlowCatalyst-Signature", sig).h("X-FlowCatalyst-Timestamp", ts).build());
    c.add(new B("timestamp header sent twice").h("X-FlowCatalyst-Signature", sig).h("X-FlowCatalyst-Timestamp", ts).h("X-FlowCatalyst-Timestamp", ts).build());
    c.add(new B("authorization header sent twice").bearer(BEARER).signed(ts).h("Authorization", "Bearer " + BEARER).h("Authorization", "Bearer " + BEARER).build());
    c.add(new B("header names are case-insensitive").h("x-flowcatalyst-signature", sig).h("X-FLOWCATALYST-TIMESTAMP", ts).build());
    // 7 bearer
    c.add(new B("bearer matching with a valid signature").bearer(BEARER).signed(ts).h("Authorization", "Bearer " + BEARER).build());
    c.add(new B("bearer wrong").bearer(BEARER).signed(ts).h("Authorization", "Bearer tok-124").build());
    c.add(new B("bearer longer than expected").bearer(BEARER).signed(ts).h("Authorization", "Bearer tok-1234").build());
    c.add(new B("bearer missing").bearer(BEARER).signed(ts).build());
    c.add(new B("bearer wrong scheme").bearer(BEARER).signed(ts).h("Authorization", "Basic " + BEARER).build());
    c.add(new B("bearer scheme lower-case").bearer(BEARER).signed(ts).h("Authorization", "bearer " + BEARER).build());
    c.add(new B("right bearer, wrong signature").bearer(BEARER).signedWith("another-secret", ts).h("Authorization", "Bearer " + BEARER).build());
    c.add(new B("no bearer configured: an Authorization header is ignored").signed(ts).h("Authorization", "Bearer whatever").build());
    // 8 requireSignature=false
    c.add(new B("requireSignature false: unsigned accepted").require(false).build());
    c.add(new B("requireSignature false: signed and right").require(false).signed(ts).build());
    c.add(new B("requireSignature false: signed but wrong is refused").require(false).signedWith("another-secret", ts).build());
    c.add(new B("requireSignature false: only a signature header is refused").require(false).h("X-FlowCatalyst-Signature", sig).build());
    c.add(new B("requireSignature false, no secret: signed delivery accepted unverified").require(false).secret(null).signedWith("another-secret", ts).build());
    c.add(new B("requireSignature false: unsigned still needs the bearer").require(false).bearer(BEARER).build());
    c.add(new B("requireSignature false: unsigned with the bearer accepted").require(false).bearer(BEARER).h("Authorization", "Bearer " + BEARER).build());
    // 9 nothing at all
    c.add(new B("requireSignature true: no headers at all").build());
    c.add(new B("requireSignature true: timestamp only").h("X-FlowCatalyst-Timestamp", ts).build());
    return c;
  }

  @Test
  void theKnownHmacVectorsAreFixed() {
    assertThat(sign("secret", "2026-08-07T09:32:12.123Z", "hello"))
        .isEqualTo("a7d62aade7a79e88792f84e035b72788a3c78a30f46ad265ec8d6fb12a4e9f91");
    assertThat(
            sign(
                "test-secret-do-not-use-in-prod",
                "2026-05-24T08:30:00.123Z",
                "{\"messageId\":\"msg_TEST123456\"}"))
        .isEqualTo("0278cfb47ccbf20a2356584cca6e076cbade089ccc961dbe553f0e768cbf9059");
  }

  @Test
  void theVectorMatchesTheGateTheJavaReceiversUse() {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "The receiver gate (flowcatalyst contract section 4, 'Signature check', and 4a slice 4). Setup per case:"
            + " signing secret (null = none configured), optional bearer token, requireSignature, and the clock"
            + " 'now'. The signature is lowercase hex HMAC-SHA256, keyed with the secret as UTF-8, over the raw"
            + " X-FlowCatalyst-Timestamp header string followed by the raw body bytes; the timestamp is ISO-8601"
            + " (Instant) or ASCII-digit Unix seconds, at most 64 characters, compared in whole seconds: refused"
            + " when older than now-300 or later than now+60. A signature that is not 64 characters is refused"
            + " before hashing; upper-case hex is lower-cased first and accepted. Headers are an ordered list of"
            + " [name, value] (names compare case-insensitively) so one can be sent twice. expect.accepted is"
            + " the gate's answer; when refused, status is 401 and the envelope is {error: UNAUTHENTICATED,"
            + " message: 'the delivery could not be authenticated', details: {reason}}; reason is one of the"
            + " codes in expect. hmacVectors are the signature function alone.");
    doc.put(
        "howToRun",
        "For each hmacVectors entry compute hex(HMAC-SHA256(secret, timestamp + body)) and compare. For each case"
            + " build the gate from setup, present headers (in order) and body at the clock 'now', and compare"
            + " accepted, status and details.reason with expect. Regenerate from Java with"
            + " -Dconformance.update=true (SignatureConformanceTest).");
    doc.put("now", NOW.toString());
    ArrayNode hmac = doc.putArray("hmacVectors");
    hmacCase(hmac, "known vector shared with the SDK verifiers", "secret", "2026-08-07T09:32:12.123Z", "hello");
    hmacCase(hmac, "router test vector computed with openssl", "test-secret-do-not-use-in-prod", "2026-05-24T08:30:00.123Z", "{\"messageId\":\"msg_TEST123456\"}");
    hmacCase(hmac, "unicode body and unix seconds timestamp", SECRET, "1791547200", "{\"name\":\"Zoë\"}");
    ObjectNode envelope = doc.putObject("envelope401");
    envelope.put("status", 401);
    envelope.put("error", "UNAUTHENTICATED");
    envelope.put("message", "the delivery could not be authenticated");
    ArrayNode out = doc.putArray("cases");
    for (Case c : cases()) {
      ObjectNode item = out.addObject();
      item.put("name", c.name());
      ObjectNode setup = item.putObject("setup");
      if (c.secret() == null) {
        setup.putNull("signingSecret");
      } else {
        setup.put("signingSecret", c.secret());
      }
      if (c.bearer() == null) {
        setup.putNull("bearerToken");
      } else {
        setup.put("bearerToken", c.bearer());
      }
      setup.put("requireSignature", c.requireSignature());
      ArrayNode headers = item.putArray("headers");
      for (String[] h : c.headers()) {
        headers.addArray().add(h[0]).add(h[1]);
      }
      item.put("body", c.body());
      item.set("expect", run(c));
    }
    JsonVectors.check(FILE, doc);
  }

  private static void hmacCase(ArrayNode out, String name, String secret, String ts, String body) {
    ObjectNode n = out.addObject();
    n.put("name", name);
    n.put("secret", secret);
    n.put("timestamp", ts);
    n.put("body", body);
    n.put("hex", sign(secret, ts, body));
  }

  private static ObjectNode run(Case c) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (String[] h : c.headers()) {
      headers.computeIfAbsent(h[0], k -> new ArrayList<>()).add(h[1]);
    }
    byte[] body = c.body().getBytes(StandardCharsets.UTF_8);
    RawRequest raw =
        new RawRequest(
            Method.POST,
            "/fc/dispatch",
            null,
            headers,
            new ByteArrayInputStream(body),
            body.length,
            1_000_000,
            "HTTP/1.1");
    Request request = Request.of(raw);
    ReceiverGate gate =
        new ReceiverGate(
            c.secret() == null ? null : Secret.of(c.secret()),
            c.bearer() == null ? null : Secret.of(c.bearer()),
            c.requireSignature(),
            () -> NOW);
    DeliverySignature.Verdict verdict = gate.verify(request);
    ObjectNode expect = JsonVectors.mapper().createObjectNode();
    if (verdict instanceof DeliverySignature.Invalid invalid) {
      Request again = Request.of(rawAgain(c));
      Optional<Response> refused = gate.refuse(again, "/fc/dispatch");
      assertThat(refused).as(c.name()).isPresent();
      assertThat(refused.get().status()).isEqualTo(401);
      expect.put("accepted", false);
      expect.put("status", 401);
      expect.put("reason", invalid.failure().name());
    } else {
      expect.put("accepted", true);
    }
    return expect;
  }

  private static RawRequest rawAgain(Case c) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (String[] h : c.headers()) {
      headers.computeIfAbsent(h[0], k -> new ArrayList<>()).add(h[1]);
    }
    byte[] body = c.body().getBytes(StandardCharsets.UTF_8);
    return new RawRequest(
        Method.POST, "/fc/dispatch", null, headers, new ByteArrayInputStream(body), body.length, 1_000_000, "HTTP/1.1");
  }
}
