package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

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
 * Published in the test-jar of {@code framework-flowcatalyst}, next to {@link FakePlatform}.
 */
public final class ReceiverTesting {

  private ReceiverTesting() {}

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
