package io.flowcatalyst.framework.httpclient.module;

import io.flowcatalyst.framework.httpclient.ClientObserver;
import io.flowcatalyst.framework.httpclient.HttpClientException;
import io.flowcatalyst.framework.httpclient.RequestMethod;
import io.flowcatalyst.framework.metrics.Counter;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.Timer;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Records every attempt as {@code fw_http_client_requests_total} and its duration timer. */
final class ClientMetrics implements ClientObserver {

  private final Counter requests;
  private final Timer durations;

  ClientMetrics(Metrics metrics) {
    this.requests =
        metrics.counter(
            "fw_http_client_requests_total",
            "Outgoing HTTP attempts by service, method and outcome (status, or failure kind).",
            "service",
            "method",
            "outcome");
    this.durations =
        metrics.timer(
            "fw_http_client_request_duration_seconds",
            "Outgoing HTTP attempt duration by service and method.",
            "service",
            "method");
  }

  @Override
  public void attempted(
      String service,
      RequestMethod method,
      String pathTemplate,
      int status,
      HttpClientException.@Nullable Kind failure,
      long nanos,
      int attempt) {
    String outcome = failure == null ? Integer.toString(status) : failure.name().toLowerCase(Locale.ROOT);
    requests.labels(service, method.name(), outcome).inc();
    durations.labels(service, method.name()).recordNanos(nanos);
  }

  /**
   * An observer for one client that can be retired: after {@link Scoped#close()} its attempts are
   * dropped and the client's series are removed, so a removed client leaves no meters behind.
   */
  Scoped scoped() {
    return new Scoped();
  }

  /** Records for one registered client until closed. */
  final class Scoped implements ClientObserver {
    private boolean closed;

    @Override
    public synchronized void attempted(
        String service,
        RequestMethod method,
        String pathTemplate,
        int status,
        HttpClientException.@Nullable Kind failure,
        long nanos,
        int attempt) {
      if (!closed) {
        ClientMetrics.this.attempted(service, method, pathTemplate, status, failure, nanos, attempt);
      }
    }

    /** Stops recording and removes the service's series; later attempts in flight are not recorded. */
    synchronized void close(String service) {
      if (!closed) {
        closed = true;
        requests.removeSeries("service", service);
        durations.removeSeries("service", service);
      }
    }
  }
}
