package io.flowcatalyst.framework.usecase;

import java.util.regex.Pattern;

/**
 * The qualified type of an event, {@code application:domain:aggregate:action}, from which the
 * source, subject and message group are derived.
 *
 * @param application the application code
 * @param domain the domain (sub-domain) within it
 * @param aggregate the aggregate the event is about
 * @param action what happened, for example {@code created}
 */
public record EventType(String application, String domain, String aggregate, String action) {

  private static final Pattern SEGMENT = Pattern.compile("[a-z][a-z0-9-]*");

  /** Each segment matches {@code [a-z][a-z0-9-]*}. */
  public EventType {
    for (String segment : new String[] {application, domain, aggregate, action}) {
      if (!SEGMENT.matcher(segment).matches()) {
        throw new IllegalArgumentException("event type segment must match [a-z][a-z0-9-]*: " + segment);
      }
    }
  }

  /**
   * The type string.
   *
   * @return {@code application:domain:aggregate:action}
   */
  public String type() {
    return application + ":" + domain + ":" + aggregate + ":" + action;
  }

  /**
   * The source.
   *
   * @return {@code application:domain}
   */
  public String source() {
    return application + ":" + domain;
  }

  /**
   * The subject for one aggregate.
   *
   * @param id the aggregate id
   * @return {@code domain.aggregate.id}
   */
  public String subject(AggregateId id) {
    return domain + "." + aggregate + "." + id.value();
  }

  /**
   * The default message group, which keeps one aggregate's events in order.
   *
   * @param id the aggregate id
   * @return {@code domain:aggregate:id}
   */
  public String messageGroup(AggregateId id) {
    return domain + ":" + aggregate + ":" + id.value();
  }
}
