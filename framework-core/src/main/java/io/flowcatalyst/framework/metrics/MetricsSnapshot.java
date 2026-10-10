package io.flowcatalyst.framework.metrics;

import java.util.List;

/**
 * What a registry holds at one moment: families sorted by name, each with its series sorted by
 * label values.
 *
 * @param families the families
 */
public record MetricsSnapshot(List<Family> families) {

  /** Copies the list. */
  public MetricsSnapshot {
    families = List.copyOf(families);
  }

  /** The instrument kinds, as the exposition format names them. */
  public enum Type {
    /** A counter. */
    COUNTER,
    /** A gauge. */
    GAUGE,
    /** A timer, exported as a histogram. */
    HISTOGRAM
  }

  /**
   * One family.
   *
   * @param name the name
   * @param help the help text
   * @param type the kind
   * @param labelNames the label names, in order
   * @param buckets the bucket upper bounds of a histogram; empty otherwise
   * @param samples one per series
   */
  public record Family(
      String name,
      String help,
      Type type,
      List<String> labelNames,
      List<Double> buckets,
      List<Sample> samples) {

    /** Copies the lists. */
    public Family {
      labelNames = List.copyOf(labelNames);
      buckets = List.copyOf(buckets);
      samples = List.copyOf(samples);
    }
  }

  /**
   * One series.
   *
   * @param labelValues the label values, in the order of the family's label names
   * @param value the value of a counter or gauge; for a histogram, the sum
   * @param cumulativeCounts for a histogram, the number of observations at or below each bucket
   *     bound, in bucket order; empty otherwise
   * @param count for a histogram, the number of observations; 0 otherwise
   */
  public record Sample(
      List<String> labelValues, double value, List<Long> cumulativeCounts, long count) {

    /** Copies the lists. */
    public Sample {
      labelValues = List.copyOf(labelValues);
      cumulativeCounts = List.copyOf(cumulativeCounts);
    }
  }
}
