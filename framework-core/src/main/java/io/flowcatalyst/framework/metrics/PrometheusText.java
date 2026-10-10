package io.flowcatalyst.framework.metrics;

import java.util.List;

/**
 * Renders a {@link MetricsSnapshot} in the Prometheus text exposition format, version 0.0.4
 * (background-and-metrics contract, section 3). Stable output: families by name, series by label
 * values.
 */
public final class PrometheusText {

  /** The content type of the format. */
  public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

  private PrometheusText() {}

  /**
   * Renders the snapshot.
   *
   * @param snapshot what to render
   * @return the exposition text, ending with a newline when not empty
   */
  public static String write(MetricsSnapshot snapshot) {
    StringBuilder out = new StringBuilder();
    for (MetricsSnapshot.Family f : snapshot.families()) {
      out.append("# HELP ").append(f.name()).append(' ').append(escapeHelp(f.help())).append('\n');
      out.append("# TYPE ").append(f.name()).append(' ').append(type(f.type())).append('\n');
      for (MetricsSnapshot.Sample s : f.samples()) {
        if (f.type() == MetricsSnapshot.Type.HISTOGRAM) {
          histogram(out, f, s);
        } else {
          line(out, f.name(), f.labelNames(), s.labelValues(), null, s.value());
        }
      }
    }
    return out.toString();
  }

  private static void histogram(StringBuilder out, MetricsSnapshot.Family f, MetricsSnapshot.Sample s) {
    List<Double> bounds = f.buckets();
    for (int i = 0; i < bounds.size(); i++) {
      line(
          out,
          f.name() + "_bucket",
          f.labelNames(),
          s.labelValues(),
          number(bounds.get(i)),
          s.cumulativeCounts().get(i));
    }
    line(out, f.name() + "_bucket", f.labelNames(), s.labelValues(), "+Inf", s.count());
    line(out, f.name() + "_sum", f.labelNames(), s.labelValues(), null, s.value());
    line(out, f.name() + "_count", f.labelNames(), s.labelValues(), null, s.count());
  }

  private static void line(
      StringBuilder out,
      String name,
      List<String> labelNames,
      List<String> labelValues,
      @org.jspecify.annotations.Nullable String le,
      double value) {
    out.append(name);
    boolean any = !labelNames.isEmpty() || le != null;
    if (any) {
      out.append('{');
      for (int i = 0; i < labelNames.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        out.append(labelNames.get(i)).append("=\"").append(escapeValue(labelValues.get(i))).append('"');
      }
      if (le != null) {
        if (!labelNames.isEmpty()) {
          out.append(',');
        }
        out.append("le=\"").append(le).append('"');
      }
      out.append('}');
    }
    out.append(' ').append(number(value)).append('\n');
  }

  private static String type(MetricsSnapshot.Type type) {
    return switch (type) {
      case COUNTER -> "counter";
      case GAUGE -> "gauge";
      case HISTOGRAM -> "histogram";
    };
  }

  /** Integral values without a fraction; others in Java's shortest round-trip form. */
  static String number(double v) {
    if (Double.isNaN(v)) {
      return "NaN";
    }
    if (Double.isInfinite(v)) {
      return v > 0 ? "+Inf" : "-Inf";
    }
    if (v == Math.rint(v) && Math.abs(v) < 1e15) {
      return Long.toString((long) v);
    }
    return Double.toString(v);
  }

  static String escapeHelp(String help) {
    return help.replace("\\", "\\\\").replace("\n", "\\n");
  }

  static String escapeValue(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
