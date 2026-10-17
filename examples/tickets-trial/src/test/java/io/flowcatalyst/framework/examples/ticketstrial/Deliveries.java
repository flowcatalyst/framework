package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What the platform sends to the receivers, signed the way it signs: hex HMAC-SHA256 over the
 * timestamp header's text followed by the body, keyed with the connection's secret (receivers
 * guide). The framework's own tests sign with a package-private helper, so an application's tests
 * write this (friction log F12).
 */
final class Deliveries {

  /** The secret the test application is configured with. Not a real secret. */
  static final String SECRET = "whsec-tickets-test-only";

  private Deliveries() {}

  static String sign(String timestamp, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
      mac.update(body.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A dispatch job for a client, as the platform delivers an event to a subscription. */
  static TestResponse dispatch(TestClient client, Instant now, String jobId, String type, String clientHeader, String envelope) {
    String ts = String.valueOf(now.getEpochSecond());
    return client
        .post("/fc/dispatch")
        .header("Content-Type", "application/json")
        .header("X-Dispatch-Job-Id", jobId)
        .header("X-Event-Type", type)
        .header("X-FlowCatalyst-Client", clientHeader)
        .header("X-FlowCatalyst-Signature", sign(ts, envelope))
        .header("X-FlowCatalyst-Timestamp", ts)
        .body(envelope)
        .send();
  }

  /** A scheduled-job firing, as the platform posts it. */
  static TestResponse fire(TestClient client, Instant now, String jobCode, String instanceId) {
    String body =
        "{\"jobId\":\"sj_1\",\"jobCode\":\""
            + jobCode
            + "\",\"instanceId\":\""
            + instanceId
            + "\",\"scheduledFor\":\""
            + now.toString()
            + "\",\"firedAt\":\""
            + now.toString()
            + "\",\"triggerKind\":\"CRON\",\"correlationId\":\"corr-"
            + instanceId
            + "\",\"tracksCompletion\":true,\"concurrent\":false}";
    String ts = String.valueOf(now.getEpochSecond());
    return client
        .post("/fc/scheduled-jobs")
        .header("Content-Type", "application/json")
        .header("X-FlowCatalyst-Signature", sign(ts, body))
        .header("X-FlowCatalyst-Timestamp", ts)
        .body(body)
        .send();
  }
}
