package io.flowcatalyst.framework.push;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.auth.Base64Url;
import io.flowcatalyst.framework.auth.Respellings;
import io.flowcatalyst.framework.conformance.JsonVectors;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The token-spelling contract for push (push contract 6.2 and 6.4): writes and checks {@code
 * conformance/push/spelling.json} and checks {@code conformance/push/issued-before.json}; then the
 * same with tickets and stream tokens issued fresh by the test.
 */
class SpellingConformanceTest {

  static final String FILE = "push/spelling.json";

  static JsonNode before() {
    return JsonVectors.read("push/issued-before.json");
  }

  /** The ticket keys of tickets.json, current then previous (the TEST KEYS published there). */
  static TicketKeys keys() {
    JsonNode k = JsonVectors.read("push/tickets.json").path("keys");
    return TicketKeys.parse(
        k.path("current").path("kid").asString()
            + ":"
            + k.path("current").path("seed").asString()
            + ","
            + k.path("previous").path("kid").asString()
            + ":"
            + k.path("previous").path("seed").asString());
  }

  /** A ticket's answer: "valid", the reason it grants nothing, or "malformed" (400). */
  static String ticketAnswer(TicketKeys keys, JsonNode setup, String raw) {
    TicketKeys.Check check =
        new TicketKeys.Check(
            setup.path("application").asString(),
            Instant.ofEpochSecond(setup.path("now").asLong()),
            "",
            Duration.ofSeconds(setup.path("maxLifetimeSeconds").asLong()));
    try {
      TicketKeys.Ticket t = keys.check(raw, check);
      return t.valid ? "valid" : t.reason;
    } catch (TicketKeys.MalformedTicketException e) {
      return "malformed";
    }
  }

  /** A stream token's answer: "valid" or the 401 code. */
  static String streamAnswer(StreamTokens tokens, Instant now, String token) {
    return switch (tokens.verify(token, now)) {
      case StreamTokens.Valid v -> "valid";
      case StreamTokens.Refused r -> r.code();
    };
  }

  /** The value with spaces after the JSON of the part, and the part signed again: a value in which the part takes another residue mod 4. */
  private static String withSpaces(String value, int part, int spaces, Function<String, String> sign) {
    String[] parts = value.split("\\.", -1);
    parts[part] = Base64Url.encode((new String(Base64Url.decode(parts[part]), StandardCharsets.UTF_8) + " ".repeat(spaces)).getBytes(StandardCharsets.UTF_8));
    String signed = String.join(".", java.util.Arrays.copyOfRange(parts, 0, parts.length - 1));
    parts[parts.length - 1] = sign.apply(signed);
    return String.join(".", parts);
  }

  /**
   * The signed parts of the value (every part but the last) re-spelled every way and signed again, over variants of the value
   * whose parts take every residue mod 4: a lenient decoder would accept each of these.
   */
  private static List<Respellings.Spelling> resigned(String issued, int signedParts, Function<String, String> verify, Function<String, String> sign) {
    List<Respellings.Spelling> out = new ArrayList<>();
    List<Integer>[] counts = new List[signedParts];
    for (int p = 0; p < signedParts; p++) {
      counts[p] = new ArrayList<Integer>();
      for (int spaces = 0; spaces < 3; spaces++) {
        String base = withSpaces(issued, p, spaces, sign);
        assertThat(verify.apply(base)).as("the base of the re-signed cases").isEqualTo("valid");
        int lastCharacters = 0;
        for (Respellings.Spelling s : Respellings.resigned(base, sign::apply)) {
          if (s.name().startsWith("part " + p + " ")) {
            out.add(new Respellings.Spelling("part " + p + " with " + spaces + " trailing spaces: " + s.name(), s.value()));
            lastCharacters += s.name().startsWith("part " + p + " ends in ") ? 1 : 0;
          }
        }
        counts[p].add(lastCharacters);
      }
      assertThat(counts[p]).as("part %d takes every count of spare bits", p).containsExactlyInAnyOrder(0, 3, 15);
    }
    return out;
  }

  private static String hmac(String secret, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64Url.encode(mac.doFinal(("fw-push-stream:" + payload).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void cases(ObjectNode into, String issued, int target, Function<String, String> verify) {
    cases(into, issued, target, verify, List.of());
  }

  private static void cases(ObjectNode into, String issued, int target, Function<String, String> verify, List<Respellings.Spelling> resigned) {
    into.put("issued", issued);
    assertThat(verify.apply(issued)).as("as issued: %s", issued).isEqualTo("valid");
    into.put("expect", "valid");
    ArrayNode respelled = into.putArray("respelled");
    for (Respellings.Spelling s : Respellings.of(issued, target)) {
      String got = verify.apply(s.value());
      assertThat(got).as("re-spelled, %s", s.name()).isNotEqualTo("valid");
      respelled.addObject().put("name", s.name()).put("value", s.value()).put("expect", got);
    }
    ArrayNode tampered = into.putArray("tampered");
    for (Respellings.Spelling s : Respellings.tampered(issued, target)) {
      String got = verify.apply(s.value());
      assertThat(got).as("tampered, %s", s.name()).isNotEqualTo("valid");
      tampered.addObject().put("name", s.name()).put("value", s.value()).put("expect", got);
    }
    if (!resigned.isEmpty()) {
      ArrayNode signedAgain = into.putArray("resigned");
      for (Respellings.Spelling s : resigned) {
        String got = verify.apply(s.value());
        assertThat(got).as("re-spelled and signed again, %s", s.name()).isNotEqualTo("valid");
        signedAgain.addObject().put("name", s.name()).put("value", s.value()).put("expect", got);
      }
    }
    assertThat(verify.apply(issued)).as("as issued, again").isEqualTo("valid");
  }

  ObjectNode document() {
    JsonNode before = before();
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "The token-spelling contract (docs/contracts/token-spelling.md) for push. tickets: the host is application 'application' with"
            + " push.ticket.key the current then the previous key of tickets.json (TEST KEYS), at 'now', with"
            + " push.stream.max-lifetime 'maxLifetimeSeconds' and no proof key; the answer is 'valid', the reason the ticket grants"
            + " nothing (the words of tickets.json), or 'malformed' (the subscribe answers 400 VALIDATION). streamTokens: verified with"
            + " push.stream.secret 'streamSecret' (a TEST SECRET) at 'now'; the answer is 'valid' or the 401 code. Each value is one of"
            + " conformance/push/issued-before.json (issued by the Go and the Java code before the rule), with every re-spelling"
            + " ('respelled': contract section 3, the signature or MAC re-spelled every way, the other parts padded, whitespace and line"
            + " breaks around the value), changes that certainly alter it ('tampered') and, for variants of the value whose parts take every residue mod 4, the signed"
            + " parts re-spelled and signed again ('resigned': the signature or MAC is valid over the new text, so a lenient decoder would"
            + " accept it), each with the answer it must get. Written by"
            + " framework-push SpellingConformanceTest.");
    doc.put(
        "howToRun",
        "Build the ticket keys and the stream-token verifier from the setup; check 'issued' and every case of 'respelled', 'tampered' and 'resigned'"
            + " exactly as written (no trimming) and compare the answer with 'expect'.");
    ObjectNode tickets = doc.putObject("tickets");
    tickets.put("application", before.path("application").asString());
    tickets.put("now", before.path("now").asLong());
    tickets.put("maxLifetimeSeconds", before.path("maxLifetimeSeconds").asLong());
    tickets.put("keys", "tickets.json: keys.current, then keys.previous");
    TicketKeys keys = keys();
    ArrayNode ticketCases = tickets.putArray("cases");
    for (JsonNode t : before.path("tickets")) {
      Function<String, String> verify = raw -> ticketAnswer(keys, tickets, raw);
      String ticket = t.path("ticket").asString();
      cases(ticketCases.addObject(), ticket, 2, verify, resigned(ticket, 2, verify, keys::signature));
    }
    ObjectNode streams = doc.putObject("streamTokens");
    streams.put("streamSecret", before.path("streamSecret").asString());
    streams.put("now", before.path("now").asLong());
    String secret = before.path("streamSecret").asString();
    StreamTokens st = new StreamTokens(secret);
    Instant now = Instant.ofEpochSecond(before.path("now").asLong());
    ArrayNode streamCases = streams.putArray("cases");
    for (JsonNode t : before.path("streamTokens")) {
      Function<String, String> verify = token -> streamAnswer(st, now, token);
      String token = t.path("token").asString();
      cases(streamCases.addObject(), token, 1, verify, resigned(token, 1, verify, payload -> hmac(secret, payload)));
    }
    return doc;
  }

  @Test
  void theVectorMatches() {
    JsonVectors.check(FILE, document());
  }

  @Test
  void valuesIssuedBeforeTheRuleStillVerify() {
    JsonNode before = before();
    TicketKeys keys = keys();
    TicketKeys.Check check =
        new TicketKeys.Check("fulfil", Instant.ofEpochSecond(before.path("now").asLong()), "", Duration.ofSeconds(before.path("maxLifetimeSeconds").asLong()));
    int tickets = 0;
    for (JsonNode t : before.path("tickets")) {
      TicketKeys.Ticket got;
      try {
        got = keys.check(t.path("ticket").asString(), check);
      } catch (TicketKeys.MalformedTicketException e) {
        throw new AssertionError(e);
      }
      assertThat(got.valid).as(t.toString()).isTrue();
      assertThat(got.client).isEqualTo(t.path("client").asString());
      assertThat(got.sub).isEqualTo(t.path("principal").asString());
      tickets++;
    }
    StreamTokens st = new StreamTokens(before.path("streamSecret").asString());
    int streams = 0;
    for (JsonNode t : before.path("streamTokens")) {
      StreamTokens.Verified v = st.verify(t.path("token").asString(), Instant.ofEpochSecond(before.path("now").asLong()));
      assertThat(v).as(t.toString()).isInstanceOf(StreamTokens.Valid.class);
      Subscription sub = ((StreamTokens.Valid) v).claims().subscription();
      assertThat(sub.client()).isEqualTo(t.path("client").asString());
      assertThat(sub.principal()).isEqualTo(t.path("principal").asString());
      assertThat(sub.device()).isEqualTo(t.path("device").asString());
      assertThat(sub.session()).isEqualTo(t.path("session").asString());
      List<String> topics = new ArrayList<>();
      t.path("topics").forEach(x -> topics.add(x.asString()));
      assertThat(sub.topics()).isEqualTo(topics);
      assertThat(((StreamTokens.Valid) v).claims().exp()).isEqualTo(t.path("exp").asLong());
      streams++;
    }
    assertThat(tickets).isEqualTo(2);
    assertThat(streams).isEqualTo(2);
  }

  @Test
  void freshTicketsAndStreamTokensVerifyOnlyInTheSpellingTheyWereIssuedIn() {
    JsonNode before = before();
    TicketKeys keys = keys();
    long now = before.path("now").asLong();
    StreamTokens st = new StreamTokens("another-test-secret-for-the-fresh-tokens-only");
    for (int i = 0; i < 12; i++) {
      // the claims' lengths vary, so the payloads take every residue mod 4
      String sub = "prn_" + "x".repeat(i);
      String ticket = keys.sign(new TicketKeys.Claims("fulfil", "acme", List.of("fulfil:order:" + i), sub, null, now - 10, now + 100, 0));
      String token = st.sign(new Subscription(List.of("fulfil:order:" + "1".repeat(i + 1)), "acme", sub, "", ""), now + 600);
      List<Respellings.Spelling> ticketCases = new ArrayList<>(Respellings.of(ticket, 2));
      ticketCases.addAll(Respellings.tampered(ticket, 2));
      assertThat(ticketAnswer(keys, before, ticket)).isEqualTo("valid");
      for (Respellings.Spelling s : ticketCases) {
        assertThat(ticketAnswer(keys, before, s.value())).as("ticket %d, %s", i, s.name()).isNotEqualTo("valid");
      }
      List<Respellings.Spelling> streamCases = new ArrayList<>(Respellings.of(token, 1));
      streamCases.addAll(Respellings.of(token, 0));
      streamCases.addAll(Respellings.tampered(token, 1));
      Instant at = Instant.ofEpochSecond(now);
      assertThat(streamAnswer(st, at, token)).isEqualTo("valid");
      for (Respellings.Spelling s : streamCases) {
        assertThat(streamAnswer(st, at, s.value())).as("stream token %d, %s", i, s.name()).isEqualTo(StreamTokens.CODE_INVALID);
      }
      assertThat(ticketAnswer(keys, before, ticket)).as("as issued, again").isEqualTo("valid");
      assertThat(streamAnswer(st, at, token)).as("as issued, again").isEqualTo("valid");
    }
  }
}
