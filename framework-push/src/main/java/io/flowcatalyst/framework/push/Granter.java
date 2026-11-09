package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.http.HttpException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Decides a subscribe: rules for the request's context, tickets for whoever holds them. */
final class Granter {

  static final String CODE_TOPIC_FORBIDDEN = "TOPIC_FORBIDDEN";
  static final String CODE_TICKET_INVALID = "TICKET_INVALID";

  private final String application;
  private final List<Rule> rules;
  private final TicketKeys keys;
  private final Duration maxLifetime;

  Granter(String application, List<Rule> rules, TicketKeys keys, Duration maxLifetime) {
    this.application = application;
    this.rules = List.copyOf(rules);
    this.keys = keys;
    this.maxLifetime = maxLifetime;
  }

  List<Rule> rules() {
    return rules;
  }

  /** What a subscribe gets. */
  record Decision(
      Subscription subscription,
      boolean platform,
      List<String> granted,
      List<Denial> denied,
      Instant expires,
      List<@Nullable String> ticketReasons) {}

  /**
   * Applies sections 6.1, 6.3 and 6.4. Topics are checked and without duplicates. The errors are the
   * answers: 400 for a malformed ticket, 401 for a request without credentials and without a valid
   * ticket (it has no client), 403 NO_TOPICS when nothing was granted. The decision lists, per
   * ticket, why it grants nothing (null: it is valid).
   */
  Decision decide(List<String> topics, List<String> rawTickets, Optional<Credentials> credentials, Instant now) {
    String proofKey = credentials.map(Credentials::proofKey).orElse("");
    TicketKeys.Check check = new TicketKeys.Check(application, now, proofKey, maxLifetime);
    List<TicketKeys.Ticket> tickets = new ArrayList<>(rawTickets.size());
    for (int i = 0; i < rawTickets.size(); i++) {
      try {
        tickets.add(keys.check(rawTickets.get(i), check));
      } catch (TicketKeys.MalformedTicketException e) {
        throw new HttpException(400, "VALIDATION", "tickets[" + i + "] is malformed: not a JWS compact with a JSON header and claims");
      }
    }
    @Nullable Context context = credentials.map(Credentials::context).orElse(null);
    String client = "";
    if (context != null) {
      client = context.client().value();
    } else {
      for (TicketKeys.Ticket t : tickets) {
        if (t.valid) {
          client = t.client;
          break;
        }
      }
      if (client.isEmpty()) {
        throw new HttpException(401, "UNAUTHENTICATED", "a subscribe without credentials needs a valid ticket");
      }
    }
    for (TicketKeys.Ticket t : tickets) {
      if (t.valid && !t.client.equals(client)) {
        t.valid = false;
        t.reason = TicketKeys.REASON_CLIENT_MISMATCH;
      }
    }
    boolean platform = client.equals(ClientId.PLATFORM.value());
    boolean[] used = new boolean[tickets.size()];
    List<String> granted = new ArrayList<>();
    List<Denial> denied = new ArrayList<>();
    for (String topic : topics) {
      List<String> segments = List.of(topic.split(":", -1));
      if (ruleAllows(context, topic)) {
        granted.add(topic);
        continue;
      }
      String code = CODE_TOPIC_FORBIDDEN;
      boolean ok = false;
      for (int i = 0; i < tickets.size(); i++) {
        TicketKeys.Ticket t = tickets.get(i);
        if (!matchesAny(t.patterns, segments)) {
          continue;
        }
        if (t.valid) {
          ok = true;
          used[i] = true;
          break;
        }
        code = CODE_TICKET_INVALID;
      }
      if (ok) {
        granted.add(topic);
      } else {
        denied.add(new Denial(topic, code));
      }
    }
    if (granted.isEmpty()) {
      throw new HttpException(403, "NO_TOPICS", "no topic was granted");
    }
    Instant expires = now.plus(maxLifetime);
    if (credentials.isPresent()) {
      Instant credentialExpiry = credentials.get().expiresAt();
      if (credentialExpiry != null && credentialExpiry.isBefore(expires)) {
        expires = credentialExpiry;
      }
    }
    String principal = "";
    for (int i = 0; i < tickets.size(); i++) {
      if (used[i]) {
        TicketKeys.Ticket t = tickets.get(i);
        if (t.streamExp.isBefore(expires)) {
          expires = t.streamExp;
        }
        if (credentials.isEmpty() && principal.isEmpty()) {
          principal = t.sub;
        }
      }
    }
    expires = expires.truncatedTo(ChronoUnit.SECONDS);
    String device = "";
    String session = "";
    if (credentials.isPresent()) {
      if (context != null && context.principal() != null) {
        principal = context.principal().value();
      }
      device = credentials.get().device();
      session = credentials.get().session();
    }
    Subscription sub = new Subscription(List.copyOf(granted), client, principal, device, session);
    List<@Nullable String> reasons = new ArrayList<>(tickets.size());
    for (TicketKeys.Ticket t : tickets) {
      reasons.add(t.valid ? null : t.reason);
    }
    return new Decision(sub, platform, List.copyOf(granted), List.copyOf(denied), expires, reasons);
  }

  private boolean ruleAllows(@Nullable Context context, String topic) {
    for (Rule r : rules) {
      if (r.allows(context, topic)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesAny(List<TopicPattern> patterns, List<String> topic) {
    for (TopicPattern p : patterns) {
      if (p.matchesAny(topic)) {
        return true;
      }
    }
    return false;
  }
}
