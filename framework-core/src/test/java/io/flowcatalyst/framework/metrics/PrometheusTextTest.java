package io.flowcatalyst.framework.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The exact exposition bytes (background-and-metrics contract, section 3). */
class PrometheusTextTest {

  @Test
  void rendersEveryKindSortedAndEscaped() {
    Metrics m = Metrics.memory(100, () -> 0L);
    Counter c = m.counter("app_requests_total", "Requests,\nby \\ path.", "route", "status");
    c.labels("/b", "200").inc(2);
    c.labels("/a \"quoted\"\nline\\", "500").inc();
    m.gauge("app_depth", "Depth.").set(0.25);
    Timer t = m.timer("app_job_seconds", "Job.", List.of(0.5, 1.0), "job");
    t.labels("x").record(Duration.ofMillis(250));
    t.labels("x").record(Duration.ofSeconds(2));
    String text = PrometheusText.write(m.snapshot());
    assertThat(text)
        .isEqualTo(
            """
            # HELP app_depth Depth.
            # TYPE app_depth gauge
            app_depth 0.25
            # HELP app_job_seconds Job.
            # TYPE app_job_seconds histogram
            app_job_seconds_bucket{job="x",le="0.5"} 1
            app_job_seconds_bucket{job="x",le="1"} 1
            app_job_seconds_bucket{job="x",le="+Inf"} 2
            app_job_seconds_sum{job="x"} 2.25
            app_job_seconds_count{job="x"} 2
            # HELP app_requests_total Requests,\\nby \\\\ path.
            # TYPE app_requests_total counter
            app_requests_total{route="/a \\"quoted\\"\\nline\\\\",status="500"} 1
            app_requests_total{route="/b",status="200"} 2
            # HELP fw_metrics_dropped_series_total Recordings dropped because a metric reached its series limit (MetricsOptions.maxSeries).
            # TYPE fw_metrics_dropped_series_total counter
            """);
  }

  @Test
  void numbers() {
    assertThat(PrometheusText.number(3)).isEqualTo("3");
    assertThat(PrometheusText.number(-2)).isEqualTo("-2");
    assertThat(PrometheusText.number(0.1)).isEqualTo("0.1");
    assertThat(PrometheusText.number(1e-4)).isEqualTo("1.0E-4");
    assertThat(PrometheusText.number(1e20)).isEqualTo("1.0E20");
    assertThat(PrometheusText.number(Double.POSITIVE_INFINITY)).isEqualTo("+Inf");
    assertThat(PrometheusText.number(Double.NEGATIVE_INFINITY)).isEqualTo("-Inf");
    assertThat(PrometheusText.number(Double.NaN)).isEqualTo("NaN");
  }

  @Test
  void theNoopDriverRendersNothing() {
    assertThat(PrometheusText.write(Metrics.noop().snapshot())).isEmpty();
  }
}
