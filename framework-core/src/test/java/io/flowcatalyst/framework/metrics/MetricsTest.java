package io.flowcatalyst.framework.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.flowcatalyst.framework.core.MonotonicClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The memory and noop drivers (background-and-metrics contract, section 2). */
class MetricsTest {

  final AtomicLong nanos = new AtomicLong();
  final MonotonicClock clock = nanos::get;
  final Metrics metrics = Metrics.memory(1000, clock);

  MetricsSnapshot.Family family(Metrics m, String name) {
    return m.snapshot().families().stream()
        .filter(f -> f.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void countersAddAndIgnoreNegativeOrNotANumberAmounts() {
    Counter c = metrics.counter("app_orders_total", "Orders placed.", "kind");
    c.labels("web").inc();
    c.labels("web").inc(2.5);
    c.labels("web").inc(-3);
    c.labels("web").inc(Double.NaN);
    c.labels("api").inc();
    MetricsSnapshot.Family f = family(metrics, "app_orders_total");
    assertThat(f.type()).isEqualTo(MetricsSnapshot.Type.COUNTER);
    assertThat(f.samples())
        .extracting(s -> s.labelValues().get(0) + "=" + s.value())
        .containsExactly("api=1.0", "web=3.5");
  }

  @Test
  void gaugesSetIncAndDec() {
    Gauge g = metrics.gauge("app_queue_depth", "Queue depth.");
    g.set(5);
    g.labels().inc(2);
    g.labels().dec(4);
    assertThat(family(metrics, "app_queue_depth").samples().get(0).value()).isEqualTo(3.0);
  }

  @Test
  void callbackGaugesAreReadOnExportAndRegisteredOnce() {
    AtomicLong depth = new AtomicLong(7);
    metrics.gauge("app_pool_size", "Pool size.", depth::get);
    assertThat(family(metrics, "app_pool_size").samples().get(0).value()).isEqualTo(7.0);
    depth.set(9);
    assertThat(family(metrics, "app_pool_size").samples().get(0).value()).isEqualTo(9.0);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.gauge("app_pool_size", "Pool size.", depth::get));
  }

  @Test
  void aCallbackThatThrowsIsLeftOut() {
    metrics.gauge("app_broken", "Broken.", () -> { throw new IllegalStateException("x"); });
    assertThat(family(metrics, "app_broken").samples()).isEmpty();
  }

  @Test
  void timersFillCumulativeBucketsSumAndCount() throws Exception {
    Timer t = metrics.timer("app_job_seconds", "Job time.", List.of(0.1, 1.0), "job");
    Timer.Series s = t.labels("export");
    s.record(Duration.ofMillis(50));
    s.record(Duration.ofMillis(100));
    s.record(Duration.ofMillis(500));
    s.record(Duration.ofSeconds(3));
    s.recordNanos(-5);
    String result =
        s.time(
            () -> {
              nanos.addAndGet(200_000_000L);
              return "done";
            });
    assertThat(result).isEqualTo("done");
    MetricsSnapshot.Sample sample = family(metrics, "app_job_seconds").samples().get(0);
    // 0, 0.05, 0.1 at or below 0.1; plus 0.5 and 0.2 at or below 1.0; 3.0 only in +Inf.
    assertThat(sample.cumulativeCounts()).containsExactly(3L, 5L);
    assertThat(sample.count()).isEqualTo(6);
    assertThat(sample.value()).isCloseTo(3.85, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void timeRecordsAlsoWhenTheWorkThrows() {
    Timer t = metrics.timer("app_fail_seconds", "Failing work.");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                t.labels()
                    .time(
                        () -> {
                          throw new IllegalStateException("boom");
                        }))
        .hasMessage("boom");
    assertThat(family(metrics, "app_fail_seconds").samples().get(0).count()).isEqualTo(1);
  }

  @Test
  void namingRules() {
    for (String bad : new String[] {"Orders_total", "1x_total", "a-b_total", "", "x".repeat(195) + "_total_"}) {
      assertThatIllegalArgumentException().as(bad).isThrownBy(() -> metrics.counter(bad, "h"));
    }
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.counter("app_orders", "h"));
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.timer("app_job", "h"));
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.gauge("app_x_total", "h"));
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.gauge("app_x_seconds", "h"));
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.counter("app_x_total", " "));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.counter("app_x_total", "h".repeat(501)));
  }

  @Test
  void labelNameRules() {
    for (String bad : new String[] {"le", "quantile", "__x", "Method", "a-b", "1a"}) {
      assertThatIllegalArgumentException()
          .as(bad)
          .isThrownBy(() -> metrics.counter("app_l_total", "h", bad));
    }
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.counter("app_l_total", "h", "a", "a"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                metrics.counter(
                    "app_l_total", "h", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"));
  }

  @Test
  void bucketRules() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.timer("app_b_seconds", "h", List.of()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.timer("app_b_seconds", "h", List.of(1.0, 1.0)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.timer("app_b_seconds", "h", List.of(0.0, 1.0)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.timer("app_b_seconds", "h", List.of(1.0, Double.POSITIVE_INFINITY)));
  }

  @Test
  void creatingTheSameInstrumentAgainReturnsTheSameFamilyAndAnythingElseIsRefused() {
    Counter a = metrics.counter("app_same_total", "Same.", "x");
    Counter b = metrics.counter("app_same_total", "Same.", "x");
    a.labels("1").inc();
    b.labels("1").inc();
    assertThat(family(metrics, "app_same_total").samples().get(0).value()).isEqualTo(2.0);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.counter("app_same_total", "Other help.", "x"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.counter("app_same_total", "Same.", "y"));
    // The suffix rules keep counter, gauge and timer names apart; a plain and a callback gauge can
    // still collide.
    metrics.gauge("app_g", "G.");
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.gauge("app_g", "G.", () -> 1.0));
    metrics.gauge("app_cb", "C.", () -> 1.0);
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.gauge("app_cb", "C."));
  }

  @Test
  void aWrongNumberOfLabelValuesThrowsButValuesThemselvesNever() {
    Counter c = metrics.counter("app_v_total", "Values.", "a", "b");
    assertThatIllegalArgumentException().isThrownBy(() -> c.labels("one"));
    assertThatIllegalArgumentException().isThrownBy(c::inc);
    c.labels(null, "x".repeat(500)).inc();
    MetricsSnapshot.Sample s = family(metrics, "app_v_total").samples().get(0);
    assertThat(s.labelValues().get(0)).isEmpty();
    assertThat(s.labelValues().get(1)).hasSize(200);
  }

  @Test
  void removingSeriesByLabelValueFreesThemAndTheLimit() {
    Metrics small = Metrics.memory(3, clock);
    Counter c = small.counter("app_users_total", "Per user.", "user", "kind");
    Timer t = small.timer("app_users_seconds", "Per user.", "user");
    for (int i = 0; i < 3; i++) {
      c.labels("u0", "k" + i).inc();
    }
    t.labels("u0").recordNanos(5);
    assertThat(c.removeSeries("user", "u0")).isEqualTo(3);
    assertThat(t.removeSeries("user", "u0")).isEqualTo(1);
    assertThat(c.removeSeries("user", "u0")).isZero();
    assertThat(family(small, "app_users_total").samples()).isEmpty();
    assertThat(family(small, "app_users_seconds").samples()).isEmpty();
    for (int i = 0; i < 3; i++) {
      c.labels("u1", "k" + i).inc(); // room again
    }
    assertThat(family(small, "app_users_total").samples()).hasSize(3);
    assertThatIllegalArgumentException().isThrownBy(() -> c.removeSeries("nope", "x"));
  }

  @Test
  void theNumberOfSeriesIsBoundedAndDropsAreCounted() {
    Metrics small = Metrics.memory(3, clock);
    Counter c = small.counter("app_users_total", "Per user, which is a mistake.", "user");
    for (int i = 0; i < 10; i++) {
      c.labels("u" + i).inc();
    }
    c.labels("u0").inc(); // an existing series still records
    assertThat(family(small, "app_users_total").samples()).hasSize(3);
    assertThat(family(small, "app_users_total").samples().get(0).value()).isEqualTo(2.0);
    MetricsSnapshot.Family dropped = family(small, "fw_metrics_dropped_series_total");
    assertThat(dropped.samples()).singleElement().satisfies(s -> {
      assertThat(s.labelValues()).containsExactly("app_users_total");
      assertThat(s.value()).isEqualTo(7.0);
    });
  }

  @Test
  void seriesCreatedConcurrentlyNeverExceedTheBound() throws Exception {
    Metrics small = Metrics.memory(50, clock);
    Counter c = small.counter("app_race_total", "Race.", "k");
    List<Thread> threads = new java.util.ArrayList<>();
    for (int t = 0; t < 16; t++) {
      int offset = t;
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    for (int i = 0; i < 100; i++) {
                      c.labels("k" + (i * 16 + offset)).inc();
                    }
                  }));
    }
    for (Thread t : threads) {
      t.join();
    }
    assertThat(family(small, "app_race_total").samples()).hasSize(50);
  }

  @Test
  void theNoopDriverChecksNamesAndRecordsNothing() {
    Metrics noop = Metrics.noop();
    assertThat(noop.readable()).isFalse();
    assertThat(metrics.readable()).isTrue();
    assertThatIllegalArgumentException().isThrownBy(() -> noop.counter("bad", "h"));
    Counter c = noop.counter("app_n_total", "N.", "a");
    assertThatIllegalArgumentException().isThrownBy(() -> c.labels());
    c.labels("x").inc();
    noop.timer("app_n_seconds", "N.").record(Duration.ofSeconds(1));
    noop.gauge("app_n", "N.").set(4);
    assertThat(noop.snapshot().families()).isEmpty();
  }

  @Test
  void maxSeriesIsChecked() {
    assertThatIllegalArgumentException().isThrownBy(() -> Metrics.memory(0, clock));
    assertThatIllegalArgumentException().isThrownBy(() -> Metrics.memory(100_001, clock));
  }
}
