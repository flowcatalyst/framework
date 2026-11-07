package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the receivers what the platform sends them, signed the way the platform signs, for an
 * application's own tests. The signature is the hex HMAC-SHA256 of the timestamp header's text
 * followed by the body, keyed with the connection's secret (receivers guide, "Security properties of
 * the signature check"); the timestamp is Unix seconds, taken from the instant the application's
 * clock reports, so the delivery is inside the tolerance.
 *
 * <pre>{@code
 * TestResponse r = ReceiverTesting.dispatch(client, SECRET, now, "dj_1", "orders:billing:invoice:created", "clt_1:acme", envelope);
 * }</pre>
 *
 * <p>{@link Delivery} builds what the platform sends for a job the application wrote to its outbox
 * ({@link Delivery#ofJob}) or for an event fanned out to a subscription ({@link Delivery#ofEvent}):
 * the job headers, the client header and the envelope; {@link #deliver} signs and sends it. {@link
 * #firingBody} and {@link #fire} do the same for a scheduled-job firing.
 *
 * <pre>{@code
 * String row = pg.query("SELECT payload FROM outbox_messages WHERE type = 'DISPATCH_JOB'");
 * TestResponse r = ReceiverTesting.deliver(client, SECRET, now, ReceiverTesting.Delivery.ofJob(row));
 * }</pre>
 *
 * Published in the test-jar of {@code framework-flowcatalyst}, next to {@link FakePlatform}.
 */
public final class ReceiverTesting {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ReceiverTesting() {}

  /**
   * One dispatch job as the platform delivers it to {@code POST /fc/dispatch}: the job headers, the
   * client header and the body, unsigned ({@link #deliver} and {@link #headers} sign it).
   *
   * <p>The platform resolves a client code to its platform id; this fake stands in {@code clt_} plus
   * the code for the id, so the client header is {@code clt_<code>:<code>}. Like the platform, it
   * sends the client header and the envelope's {@code clientCode} only when the job has a client
   * code: a platform-scoped job has neither. A job that names only a {@code clientId} (a {@code
   * platformClientId}) is delivered without them, because the fake cannot find that client's code.
   *
   * @param jobId {@code X-Dispatch-Job-Id}
   * @param eventType {@code X-Event-Type}: the job's code
   * @param clientHeader {@code X-FlowCatalyst-Client} ({@code id:code}), or null for none
   * @param body the envelope, or for a data-only job the payload alone
   */
  public record Delivery(String jobId, String eventType, @Nullable String clientHeader, String body) {

    /**
     * A job the application wrote to its outbox, as the platform delivers it: the {@code
     * DISPATCH_JOB} row's payload (the dispatch-jobs batch item).
     *
     * @param batchItem the row's {@code payload} text
     * @return the delivery, as the first attempt
     * @throws IllegalArgumentException when the text is not a batch item with an {@code id} and a
     *     {@code code}
     */
    public static Delivery ofJob(String batchItem) {
      JsonNode item = read(batchItem);
      String id = required(item, "id");
      String code = required(item, "code");
      String clientCode = text(item, "clientCode");
      String clientId = text(item, "clientId");
      if (clientId == null && clientCode != null) {
        clientId = "clt_" + clientCode;
      }
      String payload = item.has("payload") && item.get("payload").isString() ? item.get("payload").asString() : "{}";
      if (item.path("dataOnly").asBoolean(false)) {
        return new Delivery(id, code, header(clientId, clientCode), payload);
      }
      Map<String, Object> envelope = new TreeMap<>(); // the platform marshals a map: sorted keys
      envelope.put("id", id);
      envelope.put("type", code);
      envelope.put("attemptNumber", 1);
      putText(envelope, "dataContentType", text(item, "payloadContentType"));
      putText(envelope, "source", text(item, "source"));
      putText(envelope, "subject", text(item, "subject"));
      putText(envelope, "correlationId", text(item, "correlationId"));
      putText(envelope, "messageGroup", text(item, "messageGroup"));
      putText(envelope, "clientId", clientId);
      putText(envelope, "clientCode", clientCode);
      envelope.put("data", dataOf(payload));
      return new Delivery(id, code, header(clientId, clientCode), JSON.writeValueAsString(envelope));
    }

    /**
     * An event the application wrote to its outbox, as the platform delivers it to a subscription:
     * the {@code EVENT} row's payload, under a job id the test chooses.
     *
     * @param eventRow the row's {@code payload} text
     * @param jobId the dispatch job's id
     * @return the delivery, as the first attempt
     * @throws IllegalArgumentException when the text is not an event row's payload
     */
    public static Delivery ofEvent(String eventRow, String jobId) {
      JsonNode event = read(eventRow);
      String type = required(event, "type");
      String clientCode = text(event, "clientCode");
      if (clientCode != null && clientCode.equals(io.flowcatalyst.framework.context.ClientId.PLATFORM.value())) {
        clientCode = null;
      }
      String clientId = clientCode == null ? null : "clt_" + clientCode;
      Map<String, Object> envelope = new TreeMap<>();
      envelope.put("id", jobId);
      envelope.put("type", type);
      envelope.put("attemptNumber", 1);
      envelope.put("dataContentType", "application/json");
      putText(envelope, "source", text(event, "source"));
      putText(envelope, "subject", text(event, "subject"));
      putText(envelope, "correlationId", text(event, "correlationId"));
      putText(envelope, "messageGroup", text(event, "messageGroup"));
      putText(envelope, "clientId", clientId);
      putText(envelope, "clientCode", clientCode);
      String data = text(event, "data");
      envelope.put("data", dataOf(data == null ? "{}" : data));
      return new Delivery(jobId, type, header(clientId, clientCode), JSON.writeValueAsString(envelope));
    }

    /**
     * The headers the platform sends with this delivery, signed.
     *
     * @param secret the signing secret the receiver is configured with
     * @param now the application's clock's reading
     * @return the job, client, content-type, signature and timestamp headers
     */
    public Map<String, String> headers(String secret, Instant now) {
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("X-Dispatch-Job-Id", jobId);
      headers.put("X-Event-Type", eventType);
      if (clientHeader != null) {
        headers.put("X-FlowCatalyst-Client", clientHeader);
      }
      headers.putAll(ReceiverTesting.headers(secret, now, body));
      return headers;
    }

    private static @Nullable String header(@Nullable String clientId, @Nullable String clientCode) {
      return clientId == null || clientCode == null ? null : clientId + ":" + clientCode;
    }
  }

  /**
   * Signs a delivery and sends it to {@code POST /fc/dispatch}.
   *
   * @param client the application's routes
   * @param secret the signing secret the receiver is configured with
   * @param now the application's clock's reading
   * @param delivery what the platform sends
   * @return the receiver's answer
   */
  public static TestResponse deliver(TestClient client, String secret, Instant now, Delivery delivery) {
    TestClient.Call call = client.post("/fc/dispatch");
    for (Map.Entry<String, String> h : delivery.headers(secret, now).entrySet()) {
      call = call.header(h.getKey(), h.getValue());
    }
    return call.body(delivery.body()).send();
  }

  /**
   * The body of a scheduled-job firing, as the platform posts it: a cron firing at {@code now} that
   * tracks completion and is not concurrent, with job id {@code sj_1} and correlation id {@code
   * corr-<instanceId>}.
   *
   * @param now the firing time (both {@code scheduledFor} and {@code firedAt})
   * @param jobCode the scheduled job's code
   * @param instanceId the instance id
   * @return the JSON body
   */
  public static String firingBody(Instant now, String jobCode, String instanceId) {
    Map<String, Object> firing = new LinkedHashMap<>();
    firing.put("jobId", "sj_1");
    firing.put("jobCode", jobCode);
    firing.put("instanceId", instanceId);
    firing.put("scheduledFor", now.toString());
    firing.put("firedAt", now.toString());
    firing.put("triggerKind", "CRON");
    firing.put("correlationId", "corr-" + instanceId);
    firing.put("tracksCompletion", true);
    firing.put("concurrent", false);
    return JSON.writeValueAsString(firing);
  }

  /**
   * Fires a scheduled job: {@link #firingBody}, signed, to {@code POST /fc/scheduled-jobs}.
   *
   * @param client the application's routes
   * @param secret the signing secret the receiver is configured with
   * @param now the application's clock's reading, also the firing time
   * @param jobCode the scheduled job's code
   * @param instanceId the instance id
   * @return the receiver's answer ({@code 202} when it was accepted)
   */
  public static TestResponse fire(TestClient client, String secret, Instant now, String jobCode, String instanceId) {
    return scheduledJob(client, secret, now, firingBody(now, jobCode, instanceId));
  }

  /**
   * The content-type, signature and timestamp headers the platform sends with a body, for a test
   * that posts over a real socket.
   *
   * @param secret the signing secret the receiver is configured with
   * @param now the application's clock's reading
   * @param body the raw body, as sent
   * @return the headers
   */
  public static Map<String, String> headers(String secret, Instant now, String body) {
    String ts = timestamp(now);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("X-FlowCatalyst-Signature", signature(secret, ts, body));
    headers.put("X-FlowCatalyst-Timestamp", ts);
    return headers;
  }

  private static JsonNode read(String text) {
    try {
      JsonNode node = JSON.readTree(text);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("not a JSON object");
      }
      return node;
    } catch (JacksonException e) {
      throw new IllegalArgumentException("not JSON: " + e.getOriginalMessage(), e);
    }
  }

  private static String required(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null) {
      throw new IllegalArgumentException("no " + field);
    }
    return value;
  }

  private static @Nullable String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.asString() : null;
  }

  private static void putText(Map<String, Object> map, String key, @Nullable String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  /** The payload embedded as JSON when it parses, else as a string (the platform's rule). */
  private static Object dataOf(String payload) {
    try {
      return JSON.readTree(payload);
    } catch (JacksonException e) {
      return payload;
    }
  }

  /**
   * The timestamp header the platform would send at an instant.
   *
   * @param now the instant, which must be the application's own clock's reading
   * @return Unix seconds as text
   */
  public static String timestamp(Instant now) {
    return String.valueOf(now.getEpochSecond());
  }

  /**
   * The signature header for a body.
   *
   * @param secret the signing secret the receiver is configured with
   * @param timestamp the timestamp header's text, as sent
   * @param body the raw body, as sent
   * @return 64 lowercase hex characters
   */
  public static String signature(String secret, String timestamp, String body) {
    return DeliverySignature.hmacHex(secret, timestamp, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Delivers a dispatch job to {@code POST /fc/dispatch}, signed.
   *
   * @param client the application's routes
   * @param secret the signing secret the receiver is configured with
   * @param now the application's clock's reading
   * @param jobId the dispatch job id ({@code X-Dispatch-Job-Id})
   * @param eventType the job's code ({@code X-Event-Type})
   * @param clientHeader {@code X-FlowCatalyst-Client}, for example {@code clt_1:acme}; null for a platform-scoped job
   * @param body the envelope, as the platform sends it
   * @return the receiver's answer
   */
  public static TestResponse dispatch(
      TestClient client, String secret, Instant now, String jobId, String eventType, @Nullable String clientHeader, String body) {
    String ts = timestamp(now);
    TestClient.Call call =
        client
            .post("/fc/dispatch")
            .header("Content-Type", "application/json")
            .header("X-Dispatch-Job-Id", jobId)
            .header("X-Event-Type", eventType)
            .header("X-FlowCatalyst-Signature", signature(secret, ts, body))
            .header("X-FlowCatalyst-Timestamp", ts);
    if (clientHeader != null) {
      call = call.header("X-FlowCatalyst-Client", clientHeader);
    }
    return call.body(body).send();
  }

  /**
   * Delivers a scheduled-job firing to {@code POST /fc/scheduled-jobs}, signed.
   *
   * @param client the application's routes
   * @param secret the signing secret the receiver is configured with
   * @param now the application's clock's reading
   * @param body the firing, as the platform sends it
   * @return the receiver's answer ({@code 202} when it was accepted)
   */
  public static TestResponse scheduledJob(TestClient client, String secret, Instant now, String body) {
    String ts = timestamp(now);
    return client
        .post("/fc/scheduled-jobs")
        .header("Content-Type", "application/json")
        .header("X-FlowCatalyst-Signature", signature(secret, ts, body))
        .header("X-FlowCatalyst-Timestamp", ts)
        .body(body)
        .send();
  }
}
