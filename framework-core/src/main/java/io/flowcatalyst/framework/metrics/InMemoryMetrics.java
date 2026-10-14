package io.flowcatalyst.framework.metrics;

import io.flowcatalyst.framework.core.MonotonicClock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code memory} and {@code noop} drivers: one class, so the two check names identically. With
 * {@code recording} false every series is a no-op and the snapshot is empty.
 */
final class InMemoryMetrics implements Metrics {

  private static final Logger LOG = LoggerFactory.getLogger(InMemoryMetrics.class);

  static final String DROPPED = "fw_metrics_dropped_series_total";
  static final int NAME_MAX = 200;
  static final int HELP_MAX = 500;
  static final int LABELS_MAX = 10;
  static final int VALUE_MAX = 200;
  static final int BUCKETS_MAX = 50;
  static final int MAX_SERIES_LIMIT = 100_000;

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

  private static final Counter.Series NOOP_COUNTER =
      new Counter.Series() {
        @Override
        public void inc() {}

        @Override
        public void inc(double amount) {}
      };

  private static final Gauge.Series NOOP_GAUGE =
      new Gauge.Series() {
        @Override
        public void set(double value) {}

        @Override
        public void inc(double delta) {}
      };

  private final boolean recording;
  private final int maxSeries;
  private final MonotonicClock clock;
  private final Map<String, Family<?>> families = new ConcurrentHashMap<>();
  private final Timer.Series noopTimer;
  private final @Nullable Family<CounterCell> dropped;

  InMemoryMetrics(boolean recording, int maxSeries, MonotonicClock clock) {
    if (maxSeries < 1 || maxSeries > MAX_SERIES_LIMIT) {
      throw new IllegalArgumentException("max series must be 1 to " + MAX_SERIES_LIMIT);
    }
    this.recording = recording;
    this.maxSeries = maxSeries;
    this.clock = clock;
    this.noopTimer = new TimerCell(new double[0], clock, false);
    this.dropped =
        recording
            ? register(
                DROPPED,
                "Recordings dropped because a metric reached app.metrics.max-series.",
                MetricsSnapshot.Type.COUNTER,
                new String[] {"metric"},
                List.of(),
                CounterCell::new)
            : null;
  }

  // ---- creating instruments -----------------------------------------------------------------

  @Override
  public Counter counter(String name, String help, String... labelNames) {
    if (!name.endsWith("_total")) {
      throw new IllegalArgumentException("counter name must end in _total: " + name);
    }
    Family<CounterCell> f =
        register(name, help, MetricsSnapshot.Type.COUNTER, labelNames, List.of(), CounterCell::new);
    return values -> {
      CounterCell cell = f.series(values);
      return cell == null ? NOOP_COUNTER : cell;
    };
  }

  @Override
  public Gauge gauge(String name, String help, String... labelNames) {
    checkGaugeName(name);
    Family<GaugeCell> f =
        register(name, help, MetricsSnapshot.Type.GAUGE, labelNames, List.of(), GaugeCell::new);
    return values -> {
      GaugeCell cell = f.series(values);
      return cell == null ? NOOP_GAUGE : cell;
    };
  }

  @Override
  public void gauge(String name, String help, DoubleSupplier value) {
    checkGaugeName(name);
    checkHelp(name, help);
    Family<GaugeCell> f =
        new Family<>(name, help, MetricsSnapshot.Type.GAUGE, List.of(), List.of(), GaugeCell::new);
    f.callback = value;
    if (families.putIfAbsent(name, f) != null) {
      throw new IllegalArgumentException(
          "metric " + name + " already exists; a callback gauge can be registered once");
    }
  }

  @Override
  public Timer timer(String name, String help, String... labelNames) {
    return timer(name, help, DEFAULT_BUCKETS, labelNames);
  }

  @Override
  public Timer timer(String name, String help, List<Double> buckets, String... labelNames) {
    if (!name.endsWith("_seconds")) {
      throw new IllegalArgumentException("timer name must end in _seconds: " + name);
    }
    double[] bounds = checkBuckets(name, buckets);
    Family<TimerCell> f =
        register(
            name,
            help,
            MetricsSnapshot.Type.HISTOGRAM,
            labelNames,
            buckets,
            () -> new TimerCell(bounds, clock, true));
    return values -> {
      TimerCell cell = f.series(values);
      return cell == null ? noopTimer : cell;
    };
  }

  private <S> Family<S> register(
      String name,
      String help,
      MetricsSnapshot.Type type,
      String[] labelNames,
      List<Double> buckets,
      Supplier<S> factory) {
    checkName(name);
    checkHelp(name, help);
    List<String> labels = checkLabels(name, labelNames);
    Family<S> fresh = new Family<>(name, help, type, labels, List.copyOf(buckets), factory);
    Family<?> existing = families.putIfAbsent(name, fresh);
    if (existing == null) {
      return fresh;
    }
    if (!existing.sameDefinition(fresh)) {
      throw new IllegalArgumentException(
          "metric "
              + name
              + " already exists with another type, help, label names or buckets");
    }
    @SuppressWarnings("unchecked")
    Family<S> same = (Family<S>) existing;
    return same;
  }

  // ---- validation -----------------------------------------------------------------------------

  private static void checkName(String name) {
    if (name.length() > NAME_MAX || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "metric name must match [a-z][a-z0-9_]*, at most " + NAME_MAX + " characters: " + name);
    }
  }

  private static void checkGaugeName(String name) {
    checkName(name);
    if (name.endsWith("_total") || name.endsWith("_seconds")) {
      throw new IllegalArgumentException(
          "a gauge name must not end in _total or _seconds (use a counter or a timer): " + name);
    }
  }

  private static void checkHelp(String name, String help) {
    if (help.isBlank() || help.length() > HELP_MAX) {
      throw new IllegalArgumentException(
          "metric " + name + ": help must be 1 to " + HELP_MAX + " characters");
    }
  }

  private static List<String> checkLabels(String name, String[] labelNames) {
    if (labelNames.length > LABELS_MAX) {
      throw new IllegalArgumentException("metric " + name + ": at most " + LABELS_MAX + " labels");
    }
    Set<String> seen = new HashSet<>();
    for (String label : labelNames) {
      if (!NAME.matcher(label).matches()
          || label.length() > NAME_MAX
          || label.startsWith("__")
          || label.equals("le")
          || label.equals("quantile")) {
        throw new IllegalArgumentException(
            "metric "
                + name
                + ": label name must match [a-z][a-z0-9_]*, not start with __ and not be le or"
                + " quantile: "
                + label);
      }
      if (!seen.add(label)) {
        throw new IllegalArgumentException("metric " + name + ": label " + label + " twice");
      }
    }
    return List.of(labelNames);
  }

  private static double[] checkBuckets(String name, List<Double> buckets) {
    if (buckets.isEmpty() || buckets.size() > BUCKETS_MAX) {
      throw new IllegalArgumentException(
          "timer " + name + ": 1 to " + BUCKETS_MAX + " bucket bounds");
    }
    double[] out = new double[buckets.size()];
    double previous = 0;
    for (int i = 0; i < out.length; i++) {
      double b = buckets.get(i);
      if (!(b > previous) || Double.isInfinite(b)) {
        throw new IllegalArgumentException(
            "timer " + name + ": bucket bounds must be positive, finite and strictly increasing");
      }
      out[i] = b;
      previous = b;
    }
    return out;
  }

  static String cut(@Nullable String value) {
    if (value == null) {
      return "";
    }
    return value.length() > VALUE_MAX ? value.substring(0, VALUE_MAX) : value;
  }

  // ---- reading --------------------------------------------------------------------------------

  @Override
  public boolean readable() {
    return recording;
  }

  @Override
  public MetricsSnapshot snapshot() {
    if (!recording) {
      return new MetricsSnapshot(List.of());
    }
    List<Family<?>> sorted = new ArrayList<>(families.values());
    sorted.sort(Comparator.comparing(f -> f.name));
    List<MetricsSnapshot.Family> out = new ArrayList<>();
    for (Family<?> f : sorted) {
      out.add(f.snapshot());
    }
    return new MetricsSnapshot(out);
  }

  // ---- families and cells ---------------------------------------------------------------------

  /** Something that can describe its current value as a sample. */
  private interface Cell {
    MetricsSnapshot.Sample sample(List<String> labelValues);
  }

  private final class Family<S> {
    final String name;
    final String help;
    final MetricsSnapshot.Type type;
    final List<String> labelNames;
    final List<Double> buckets;
    final Supplier<S> factory;
    final Map<List<String>, S> series = new ConcurrentHashMap<>();
    final AtomicInteger size = new AtomicInteger();
    final AtomicBoolean warned = new AtomicBoolean();
    volatile @Nullable DoubleSupplier callback;
    final AtomicBoolean callbackWarned = new AtomicBoolean();

    Family(
        String name,
        String help,
        MetricsSnapshot.Type type,
        List<String> labelNames,
        List<Double> buckets,
        Supplier<S> factory) {
      this.name = name;
      this.help = help;
      this.type = type;
      this.labelNames = labelNames;
      this.buckets = buckets;
      this.factory = factory;
    }

    boolean sameDefinition(Family<?> other) {
      return callback == null
          && other.callback == null
          && type == other.type
          && help.equals(other.help)
          && labelNames.equals(other.labelNames)
          && buckets.equals(other.buckets);
    }

    /** The series for the values, or null when it is not recorded (noop, or the family is full). */
    @Nullable S series(String[] values) {
      if (values.length != labelNames.size()) {
        throw new IllegalArgumentException(
            "metric "
                + name
                + " has labels "
                + labelNames
                + "; got "
                + values.length
                + " values");
      }
      if (!recording) {
        return null;
      }
      String[] cleaned = new String[values.length];
      for (int i = 0; i < values.length; i++) {
        cleaned[i] = cut(values[i]);
      }
      List<String> key = Arrays.asList(cleaned);
      S existing = series.get(key);
      if (existing != null) {
        return existing;
      }
      S created =
          series.computeIfAbsent(
              List.copyOf(key),
              k -> {
                if (size.incrementAndGet() > maxSeries) {
                  size.decrementAndGet();
                  return null;
                }
                return factory.get();
              });
      if (created == null) {
        dropped();
      }
      return created;
    }

    private void dropped() {
      if (warned.compareAndSet(false, true)) {
        LOG.warn(
            "metric {} reached app.metrics.max-series ({}); new label combinations are dropped",
            name,
            maxSeries);
      }
      Family<CounterCell> d = dropped;
      if (d != null && d != this) {
        CounterCell cell = d.series(new String[] {name});
        if (cell != null) {
          cell.inc();
        }
      }
    }

    MetricsSnapshot.Family snapshot() {
      List<MetricsSnapshot.Sample> samples = new ArrayList<>();
      DoubleSupplier cb = callback;
      if (cb != null) {
        try {
          samples.add(new MetricsSnapshot.Sample(List.of(), cb.getAsDouble(), List.of(), 0));
        } catch (RuntimeException e) {
          if (callbackWarned.compareAndSet(false, true)) {
            LOG.warn("callback gauge {} threw {}; it is left out", name, e.getClass().getSimpleName());
          }
        }
      }
      for (Map.Entry<List<String>, S> e : series.entrySet()) {
        samples.add(((Cell) e.getValue()).sample(e.getKey()));
      }
      samples.sort(InMemoryMetrics::compareLabels);
      return new MetricsSnapshot.Family(name, help, type, labelNames, buckets, samples);
    }
  }

  private static int compareLabels(MetricsSnapshot.Sample a, MetricsSnapshot.Sample b) {
    List<String> x = a.labelValues();
    List<String> y = b.labelValues();
    for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
      int c = x.get(i).compareTo(y.get(i));
      if (c != 0) {
        return c;
      }
    }
    return Integer.compare(x.size(), y.size());
  }

  private static final class CounterCell implements Counter.Series, Cell {
    private final DoubleAdder value = new DoubleAdder();

    @Override
    public void inc() {
      value.add(1);
    }

    @Override
    public void inc(double amount) {
      if (amount >= 0 && !Double.isInfinite(amount)) {
        value.add(amount);
      }
    }

    @Override
    public MetricsSnapshot.Sample sample(List<String> labelValues) {
      return new MetricsSnapshot.Sample(labelValues, value.sum(), List.of(), 0);
    }
  }

  private static final class GaugeCell implements Gauge.Series, Cell {
    private final AtomicLong bits = new AtomicLong(Double.doubleToLongBits(0));

    @Override
    public void set(double value) {
      bits.set(Double.doubleToLongBits(value));
    }

    @Override
    public void inc(double delta) {
      bits.updateAndGet(b -> Double.doubleToLongBits(Double.longBitsToDouble(b) + delta));
    }

    @Override
    public MetricsSnapshot.Sample sample(List<String> labelValues) {
      return new MetricsSnapshot.Sample(
          labelValues, Double.longBitsToDouble(bits.get()), List.of(), 0);
    }
  }

  private static final class TimerCell implements Timer.Series, Cell {
    private final double[] bounds;
    private final LongAdder[] perBucket;
    private final LongAdder count = new LongAdder();
    private final DoubleAdder sum = new DoubleAdder();
    private final MonotonicClock clock;
    private final boolean recording;

    TimerCell(double[] bounds, MonotonicClock clock, boolean recording) {
      this.bounds = bounds;
      this.clock = clock;
      this.recording = recording;
      this.perBucket = new LongAdder[bounds.length];
      for (int i = 0; i < bounds.length; i++) {
        perBucket[i] = new LongAdder();
      }
    }

    @Override
    public void recordNanos(long nanos) {
      if (!recording) {
        return;
      }
      double seconds = Math.max(0L, nanos) / 1_000_000_000.0;
      for (int i = 0; i < bounds.length; i++) {
        if (seconds <= bounds[i]) {
          perBucket[i].increment();
          break;
        }
      }
      count.increment();
      sum.add(seconds);
    }

    @Override
    public <T> T time(Callable<T> work) throws Exception {
      long start = clock.nanos();
      try {
        return work.call();
      } finally {
        recordNanos(clock.nanos() - start);
      }
    }

    @Override
    public MetricsSnapshot.Sample sample(List<String> labelValues) {
      List<Long> cumulative = new ArrayList<>(bounds.length);
      long running = 0;
      for (LongAdder b : perBucket) {
        running += b.sum();
        cumulative.add(running);
      }
      // count and buckets are read separately; never report fewer observations than the buckets.
      long total = Math.max(count.sum(), running);
      return new MetricsSnapshot.Sample(labelValues, sum.sum(), cumulative, total);
    }
  }

  @Override
  public String toString() {
    return "Metrics[" + (recording ? "memory" : "noop") + ", " + families.size() + " families]";
  }
}
