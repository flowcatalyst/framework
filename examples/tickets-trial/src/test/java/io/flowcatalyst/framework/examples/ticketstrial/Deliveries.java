package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.flowcatalyst.ReceiverTesting;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.time.Instant;

/**
 * What the platform sends to the receivers, signed the way it signs (the framework's {@link
 * ReceiverTesting}), with the secret and the firing's JSON of this service's tests. Until the
 * framework published that helper this class carried its own HMAC (friction log F12).
 */
final class Deliveries {

  /** The secret the test application is configured with. Not a real secret. */
  static final String SECRET = "whsec-tickets-test-only";

  private Deliveries() {}

  static String sign(String timestamp, String body) {
    return ReceiverTesting.signature(SECRET, timestamp, body);
  }

  /** A dispatch job for a client, as the platform delivers an event to a subscription. */
  static TestResponse dispatch(TestClient client, Instant now, String jobId, String type, String clientHeader, String envelope) {
    return ReceiverTesting.dispatch(client, SECRET, now, jobId, type, clientHeader, envelope);
  }

  /** The body of a scheduled-job firing, as the platform posts it. */
  static String firingBody(Instant now, String jobCode, String instanceId) {
    return "{\"jobId\":\"sj_1\",\"jobCode\":\""
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
  }

  /** A scheduled-job firing, as the platform posts it. */
  static TestResponse fire(TestClient client, Instant now, String jobCode, String instanceId) {
    return ReceiverTesting.scheduledJob(client, SECRET, now, firingBody(now, jobCode, instanceId));
  }
}
