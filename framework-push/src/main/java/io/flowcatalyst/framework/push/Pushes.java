package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.locks.LockGate;
import io.flowcatalyst.framework.usecase.InvalidPushException;
import io.flowcatalyst.framework.usecase.Push;
import io.flowcatalyst.framework.usecase.PushOutbox;
import io.flowcatalyst.framework.usecase.Tx;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The built push module: publishing, tickets, the cursor and the stream ends. Provided by
 * {@link PushModule}; the routes ({@code POST {base}/push/subscriptions},
 * {@code GET {base}/push/stream}, {@code GET {base}/push/keys}) are mounted on the application's
 * routes when the application boots.
 *
 * <p>Inside a use case, add a push to the plan instead: {@code plan.andPush(Push.of(...))}.
 */
public final class Pushes {

  /** The longest a minted ticket may be used to subscribe: {@code exp - iat}. */
  public static final Duration TICKET_TTL = Duration.ofSeconds(TicketKeys.TTL_SECONDS);

  /** The most topic patterns one ticket carries. */
  public static final int MAX_TICKET_TOPICS = TicketKeys.MAX_TOPICS;

  /** The settings the module reads once. */
  record Settings(int maxDataBytes, int maxTopics, Duration retention, Duration relayInterval, LockGate relayGate) {}

  private final String application;
  private final DataSource dataSource;
  private final Log log;
  private final Hub hub;
  private final Relay relay;
  private final Granter granter;
  private final StreamTokens tokens;
  private final TicketKeys keys;
  private final byte[] jwks;
  private final @Nullable PushAuthenticator authenticator;
  private final InstantSource clock;
  private final PushMetrics metrics;
  private final Settings settings;
  private final PushHttp http;

  Pushes(
      String application,
      DataSource dataSource,
      Log log,
      Hub hub,
      Relay relay,
      Granter granter,
      StreamTokens tokens,
      TicketKeys keys,
      @Nullable PushAuthenticator authenticator,
      InstantSource clock,
      PushMetrics metrics,
      Settings settings) {
    this.application = application;
    this.dataSource = dataSource;
    this.log = log;
    this.hub = hub;
    this.relay = relay;
    this.granter = granter;
    this.tokens = tokens;
    this.keys = keys;
    this.jwks = keys.jwks();
    this.authenticator = authenticator;
    this.clock = clock;
    this.metrics = metrics;
    this.settings = settings;
    this.http = new PushHttp(this);
  }

  Hub hub() {
    return hub;
  }

  Relay relay() {
    return relay;
  }

  Log log() {
    return log;
  }

  Granter granter() {
    return granter;
  }

  StreamTokens tokens() {
    return tokens;
  }

  byte[] jwks() {
    return jwks.clone();
  }

  @Nullable PushAuthenticator authenticator() {
    return authenticator;
  }

  InstantSource clock() {
    return clock;
  }

  PushMetrics metrics() {
    return metrics;
  }

  PushHttp http() {
    return http;
  }

  int maxTopics() {
    return settings.maxTopics();
  }

  Settings settings() {
    return settings;
  }

  /**
   * The declared topic rules (data, for a gateway to apply).
   *
   * @return the rules
   */
  public List<Rule> rules() {
    return granter.rules();
  }

  /**
   * Writes a push in a transaction the caller owns (push contract section 2), for the context's
   * client: a row of {@code fw_push_outbox} and a notification, both only if the transaction
   * commits. A use case uses {@code Plan.andPush} instead.
   *
   * @param tx the caller's transaction
   * @param context who is acting; its client is the push's client (the platform client is stored
   *     as NULL)
   * @param push the push
   * @throws InvalidPushException {@code DATA_TOO_LARGE} when the data is over
   *     {@code push.max-data-bytes}
   * @throws SQLException on a database error
   */
  public void publish(Tx tx, Context context, Push push) throws SQLException {
    publish(tx.connection(), context, push);
  }

  private void publish(Connection connection, Context context, Push push) throws SQLException {
    int bytes = push.data().getBytes(StandardCharsets.UTF_8).length;
    if (bytes > settings.maxDataBytes()) {
      throw new InvalidPushException(
          InvalidPushException.DATA_TOO_LARGE,
          "the data is " + bytes + " bytes, more than " + settings.maxDataBytes() + " (push.max-data-bytes)");
    }
    PushOutbox.insert(connection, push, context.client(), clock.instant());
  }

  /**
   * {@link #publish} in a transaction of its own on the default database instance.
   *
   * @param context who is acting
   * @param push the push
   * @throws InvalidPushException {@code DATA_TOO_LARGE} when the data is over the limit
   * @throws SQLException on a database error
   */
  public void publishAlone(Context context, Push push) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        publish(connection, context, push);
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      }
    }
  }

  /**
   * The push cursor for a snapshot (push contract section 9.1): the id of the newest push this
   * instance has dispatched, as a decimal string, {@code "0"} for an empty log. An endpoint that
   * returns state a client keeps current by pushes returns the cursor with it, and the client
   * opens its first listen with the cursor as {@code Last-Event-ID} (field-js: the
   * {@code lastEventId} option of {@code PushStream}), so a change committed between the read and
   * the listen is not missed.
   *
   * <p>Call it <em>before</em> reading the state: before the read transaction takes its snapshot
   * (in a REPEATABLE READ transaction, before its first statement; in READ COMMITTED, before the
   * state query). Every push at or below the cursor was in the log when the cursor was read, and
   * the relay logs a push only after the publishing transaction committed, so the change behind it
   * is visible to a state read that starts afterwards. A change the snapshot lacks therefore has a
   * push above the cursor, and the listen delivers it. A push for a change the snapshot already
   * holds is harmless: pushes are signals to read again.
   *
   * <p>The cursor may lag the log (this instance has not dispatched the newest pushes yet). That
   * is safe, since a lagging cursor only replays more pushes; it costs no I/O.
   *
   * @return the cursor
   */
  public String cursor() {
    return Long.toString(hub.lastId());
  }

  /** Binds a ticket further ({@link #ticketWith}). */
  public record TicketOptions(String proofKey, @Nullable Instant streamExpiry) {

    /** No binding and the default stream expiry. */
    public static final TicketOptions NONE = new TicketOptions("", null);
  }

  /**
   * Mints a ticket (push contract 6.4) granting topics (patterns as in rules, each placeholder
   * matching any one segment, at most 100) to whoever holds it, for the context's client, valid for
   * two minutes. Mint it in the application's own endpoint, after its own access check, for an
   * authenticated caller only.
   *
   * @param context who the ticket is for; its client is the stream's client, its principal the
   *     ticket's {@code sub}
   * @param topics the topic patterns, all under this application's code
   * @return the ticket (JWS compact)
   * @throws IllegalArgumentException when a pattern is not one, or not this application's
   */
  public String ticket(Context context, String... topics) {
    return ticketWith(context, TicketOptions.NONE, topics);
  }

  /**
   * {@link #ticket} with a key binding or a stream expiry.
   *
   * @param context who the ticket is for
   * @param options the binding: {@code proofKey} binds the ticket to a DPoP key ({@code cnf.jkt}:
   *     the subscribe must be authenticated with a field token bound to it), {@code streamExpiry}
   *     is the latest the stream may live ({@code stream-exp}; null for the default)
   * @param topics the topic patterns
   * @return the ticket
   */
  public String ticketWith(Context context, TicketOptions options, String... topics) {
    if (topics.length == 0 || topics.length > TicketKeys.MAX_TOPICS) {
      throw new IllegalArgumentException("a ticket grants 1 to " + TicketKeys.MAX_TOPICS + " topic patterns");
    }
    for (String t : topics) {
      TopicPattern pattern = TopicPattern.parse(t);
      if (!pattern.application().equals(application)) {
        throw new IllegalArgumentException("a ticket grants this application's topics (" + application + ") only: " + t);
      }
    }
    Instant now = clock.instant();
    String sub = context.principal() == null ? null : context.principal().value();
    Instant streamExpiry = options.streamExpiry();
    return keys.sign(
        new TicketKeys.Claims(
            application,
            context.client().value(),
            List.of(topics),
            sub,
            options.proofKey().isEmpty() ? null : options.proofKey(),
            now.getEpochSecond(),
            now.plus(TICKET_TTL).getEpochSecond(),
            streamExpiry == null ? 0 : streamExpiry.getEpochSecond()));
  }

  /**
   * Ends the open streams of a holder on every instance, with {@code auth-expired} (push contract
   * section 7: a revoked field login family, a session signed out). The clients subscribe again.
   *
   * @param holder who; it must name somebody
   * @throws IllegalArgumentException when the holder names nobody
   * @throws LogException when the other instances cannot be told
   */
  public void endStreams(Holder holder) {
    if (holder.isEmpty()) {
      throw new IllegalArgumentException("endStreams of a holder that names nobody");
    }
    hub.endHolder(holder);
    log.end(holder);
  }

  /**
   * {@link #endStreams} for one principal.
   *
   * @param principal the principal id
   */
  public void endPrincipal(String principal) {
    endStreams(Holder.ofPrincipal(principal));
  }
}
