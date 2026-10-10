package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Request metrics and the Prometheus endpoint handler (background-and-metrics contract). */
class HttpMetricsTest {

  final Metrics metrics = Metrics.memory(1000, MonotonicClock.system());

  double value(String name, List<String> labels) {
    for (MetricsSnapshot.Family f : metrics.snapshot().families()) {
      if (f.name().equals(name)) {
        for (MetricsSnapshot.Sample s : f.samples()) {
          if (s.labelValues().equals(labels)) {
            return f.type() == MetricsSnapshot.Type.HISTOGRAM ? s.count() : s.value();
          }
        }
      }
    }
    return 0;
  }

  @Test
  void requestsAreCountedByMethodRoutePatternAndStatusNeverByPath() {
    Routes routes = new Routes();
    routes.get("/orders/{id}", r -> Response.ok(Map.of("id", r.pathParam("id"))));
    routes.post("/orders", r -> { throw new HttpException(409, "TAKEN", "taken"); });
    TestClient client = TestClient.of(routes, null, metrics);
    client.get("/orders/1").send();
    client.get("/orders/2").send();
    client.post("/orders").send();
    client.get("/secret/path/of/an/attacker").send();
    client.request(Method.DELETE, "/orders/1").send();

    String total = "fw_http_server_requests_total";
    assertThat(value(total, List.of("GET", "/orders/{id}", "200"))).isEqualTo(2.0);
    assertThat(value(total, List.of("POST", "/orders", "409"))).isEqualTo(1.0);
    assertThat(value(total, List.of("GET", "<unmatched>", "404"))).isEqualTo(1.0);
    assertThat(value(total, List.of("DELETE", "<unmatched>", "405"))).isEqualTo(1.0);
    assertThat(value("fw_http_server_request_duration_seconds", List.of("GET", "/orders/{id}")))
        .isEqualTo(2.0);
    String text = io.flowcatalyst.framework.metrics.PrometheusText.write(metrics.snapshot());
    assertThat(text).doesNotContain("attacker").doesNotContain("/orders/1\"");
  }

  @Test
  void theEndpointServesTheExpositionText() {
    metrics.counter("app_things_total", "Things.").inc();
    Routes routes = new Routes();
    routes.get("/metrics", MetricsEndpoint.handler(metrics, null));
    TestResponse r = TestClient.of(routes).get("/metrics").send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.header("Content-Type")).isEqualTo("text/plain; version=0.0.4; charset=utf-8");
    assertThat(r.body()).contains("# TYPE app_things_total counter\napp_things_total 1\n");
  }

  @Test
  void withATokenOnlyTheRightBearerIsServed() {
    Routes routes = new Routes();
    routes.get("/metrics", MetricsEndpoint.handler(metrics, Secret.of("s3cr3t-token")));
    TestClient client = TestClient.of(routes);
    assertThat(client.get("/metrics").send().status()).isEqualTo(401);
    assertThat(client.get("/metrics").header("Authorization", "Bearer wrong").send().status())
        .isEqualTo(401);
    assertThat(client.get("/metrics").header("Authorization", "Basic s3cr3t-token").send().status())
        .isEqualTo(401);
    assertThat(client.get("/metrics").header("Authorization", "Bearer s3cr3t-token-").send().status())
        .isEqualTo(401);
    TestResponse denied = client.get("/metrics").header("Authorization", "Bearer x").send();
    assertThat(denied.tree().get("error").asString()).isEqualTo("UNAUTHENTICATED");
    assertThat(denied.body()).doesNotContain("s3cr3t");
    assertThat(client.get("/metrics").header("Authorization", "Bearer s3cr3t-token").send().status())
        .isEqualTo(200);
    assertThat(client.get("/metrics").header("Authorization", "bearer s3cr3t-token").send().status())
        .isEqualTo(200);
    assertThat(
            client
                .get("/metrics")
                .header("Authorization", "Bearer s3cr3t-token")
                .header("Authorization", "Bearer s3cr3t-token")
                .send()
                .status())
        .isEqualTo(401);
  }
}
