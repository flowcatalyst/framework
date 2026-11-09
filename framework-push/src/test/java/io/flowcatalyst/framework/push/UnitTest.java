package io.flowcatalyst.framework.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.context.PrincipalId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tickets, stream tokens, holders and rules without a server. */
class UnitTest {

  static final Instant NOW = TestNode.BEGIN;

  static TicketKeys keys() {
    return TicketKeys.parse(TestNode.KEY_CURRENT + "," + TestNode.KEY_PREVIOUS);
  }

  static TicketKeys.Check check(String proof) {
    return new TicketKeys.Check("fulfil", NOW, proof, Duration.ofHours(1));
  }

  static String claimsOf(String ticket) {
    return new String(Base64.getUrlDecoder().decode(ticket.split("\\.")[1]), StandardCharsets.UTF_8);
  }

  @Test
  void aMintedTicketIsEdDsaWithItsKidAndTheContractsClaimsInOrder() throws Exception {
    String t = keys().sign(new TicketKeys.Claims("fulfil", "acme", List.of("fulfil:order:{id}"), "prn_1", "jkt-1", NOW.getEpochSecond(), NOW.getEpochSecond() + 120, 0));
    assertThat(new String(Base64.getUrlDecoder().decode(t.split("\\.")[0]), StandardCharsets.UTF_8)).isEqualTo("{\"alg\":\"EdDSA\",\"kid\":\"test-2026-a\"}");
    assertThat(claimsOf(t))
        .isEqualTo("{\"iss\":\"fulfil\",\"aud\":\"fw-push\",\"client\":\"acme\",\"topics\":[\"fulfil:order:{id}\"],\"sub\":\"prn_1\",\"cnf\":{\"jkt\":\"jkt-1\"},\"iat\":1794139200,\"exp\":1794139320}");
    TicketKeys.Ticket checked = keys().check(t, check("jkt-1"));
    assertThat(checked.valid).isTrue();
    assertThat(checked.client).isEqualTo("acme");
    assertThat(checked.sub).isEqualTo("prn_1");
    assertThat(checked.streamExp).isEqualTo(NOW.plusSeconds(120).plus(Duration.ofHours(1)));
  }

  @Test
  void aTamperedTicketFailsTheSignatureCheck() throws Exception {
    String t = keys().sign(new TicketKeys.Claims("fulfil", "acme", List.of("fulfil:order:{id}"), null, null, NOW.getEpochSecond(), NOW.getEpochSecond() + 120, 0));
    String[] parts = t.split("\\.");
    String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(claimsOf(t).replace("acme", "evil").getBytes(StandardCharsets.UTF_8));
    TicketKeys.Ticket bad = keys().check(parts[0] + "." + forged + "." + parts[2], check(""));
    assertThat(bad.valid).isFalse();
    assertThat(bad.reason).isEqualTo(TicketKeys.REASON_SIGNATURE);
  }

  @Test
  void aTicketOfAnotherStrangerKeyIsRefusedByKidOrSignature() throws Exception {
    TicketKeys stranger = TicketKeys.parse("test-other:akpj827cS1gLGxwkstCQeVk6M-4kmfcyq47WcTCGpr0");
    String t = stranger.sign(new TicketKeys.Claims("fulfil", "acme", List.of("fulfil:order:{id}"), null, null, NOW.getEpochSecond(), NOW.getEpochSecond() + 120, 0));
    assertThat(keys().check(t, check("")).reason).isEqualTo(TicketKeys.REASON_KID);
  }

  @Test
  void keyConfigurationIsChecked() {
    assertThatThrownBy(() -> TicketKeys.parse("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TicketKeys.parse("a:AAAA")).hasMessageContaining("32-byte");
    assertThatThrownBy(() -> TicketKeys.parse(TestNode.KEY_CURRENT + "," + TestNode.KEY_CURRENT)).hasMessageContaining("twice");
    String seed = TestNode.KEY_CURRENT.substring(TestNode.KEY_CURRENT.indexOf(':'));
    assertThatThrownBy(() -> TicketKeys.parse("a" + seed + ",b" + seed + ",c" + seed)).hasMessageContaining("at most two");
    assertThatThrownBy(() -> TicketKeys.parse("bad kid" + seed)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aStreamTokenRoundTripsAndNamesWhoHoldsIt() {
    StreamTokens tokens = new StreamTokens(TestNode.SECRET);
    Subscription sub = new Subscription(List.of("a:b", "a:c"), "acme", "prn_1", "fwd_1", "fws_1");
    String t = tokens.sign(sub, NOW.getEpochSecond() + 60);
    StreamTokens.Valid v = (StreamTokens.Valid) tokens.verify(t, NOW);
    assertThat(v.claims().subscription()).isEqualTo(sub);
    assertThat(v.claims().exp()).isEqualTo(NOW.getEpochSecond() + 60);
    assertThat(tokens.verify(t, NOW.plusSeconds(60))).isEqualTo(new StreamTokens.Refused(StreamTokens.CODE_EXPIRED));
    assertThat(new StreamTokens("another secret of at least thirty-two characters").verify(t, NOW)).isEqualTo(new StreamTokens.Refused(StreamTokens.CODE_INVALID));
  }

  @Test
  void aHolderIsTheSamePayloadAsTheGoFramework() {
    Holder h = new Holder("prn_1", "", "fws_\"1");
    assertThat(h.toJson()).isEqualTo("{\"principal\":\"prn_1\",\"session\":\"fws_\\\"1\"}");
    assertThat(Holder.fromJson(h.toJson())).isEqualTo(h);
    assertThat(Holder.fromJson("not json").isEmpty()).isTrue();
    assertThat(Holder.fromJson("{\"principal\":\"x\",\"other\":1}")).isEqualTo(Holder.ofPrincipal("x"));
  }

  @Test
  void aRuleOnClientNeedsPrincipalOrPermission() {
    assertThatThrownBy(() -> Rule.of("fulfil:client:{client}")).hasMessageContaining("{principal}");
    Rule own = Rule.of("fulfil:client:{client}:news", "fulfil:news:view");
    Context acme = new Context(new ClientId("acme"), new PrincipalId("prn_1"), null, CorrelationId.generate(), null, null, Set.of("fulfil:news:view"));
    assertThat(own.allows(acme, "fulfil:client:acme:news")).isTrue();
    assertThat(own.allows(acme, "fulfil:client:other:news")).isFalse();
    assertThat(own.allows(null, "fulfil:client:acme:news")).isFalse();
    assertThat(Rule.open("fulfil:public").allows(null, "fulfil:public")).isTrue();
    assertThat(Rule.open("fulfil:me:{principal}").allows(null, "fulfil:me:prn_1")).isFalse();
  }
}
