package io.flowcatalyst.framework.usecase;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A dispatch job a plan writes to the outbox (write-path contract, section 7a). The platform's
 * outbox processor sends it to {@code POST /api/dispatch-jobs/batch}; the platform then delivers
 * {@link #payload()} to {@link #targetUrl()}.
 *
 * <p>Every rule the platform would enforce by refusing the whole batch is checked here, when the
 * job is built, so a job that could never be accepted is never written.
 *
 * <pre>{@code
 * DispatchJob job =
 *     DispatchJob.builder("orders:billing:export-invoice", URI.create("https://billing.example.com/export"))
 *         .jsonPayload(Map.of("invoiceId", invoice.id().value()))
 *         .messageGroup("invoice:" + invoice.id().value())
 *         .build();
 * return Plan.save(invoice, repository, event).andDispatch(job);
 * }</pre>
 *
 * @param id the job id, or null to have the writer generate one
 * @param kind the platform's job kind
 * @param code {@code application:rest}
 * @param source where the job comes from
 * @param subject what it is about, or null
 * @param targetUrl where the platform delivers it
 * @param payload the body delivered
 * @param payloadContentType its content type
 * @param dataOnly true to deliver the payload alone, false to wrap it in the platform's envelope
 * @param externalId the application's own reference, or null
 * @param eventId the platform id of a related event, or null
 * @param platformClientId the platform's id of the client, or null
 * @param subscriptionId the platform's subscription id, or null
 * @param serviceAccountId the platform's service account id, or null
 * @param dispatchPoolId the platform's dispatch pool id, or null
 * @param messageGroup the ordering group, or null
 * @param mode the group's delivery mode, or null for the platform default
 * @param sequence order within the group (1 or more), or null for the platform default
 * @param timeoutSeconds delivery timeout (1 or more), or null for the platform default
 * @param maxRetries retries (1 or more), or null for the platform default
 * @param metadata string pairs, in insertion order
 * @param queue the platform queue, or null
 * @param descriptor a short label, or null
 */
public record DispatchJob(
    @Nullable String id,
    Kind kind,
    String code,
    String source,
    @Nullable String subject,
    URI targetUrl,
    String payload,
    String payloadContentType,
    boolean dataOnly,
    @Nullable String externalId,
    @Nullable String eventId,
    @Nullable String platformClientId,
    @Nullable String subscriptionId,
    @Nullable String serviceAccountId,
    @Nullable String dispatchPoolId,
    @Nullable String messageGroup,
    @Nullable Mode mode,
    @Nullable Integer sequence,
    @Nullable Integer timeoutSeconds,
    @Nullable Integer maxRetries,
    Map<String, String> metadata,
    @Nullable Queue queue,
    @Nullable String descriptor) {

  /** The platform's job kinds. */
  public enum Kind {
    /** Fanned out from an event (the platform's default). */
    EVENT,
    /** Created by an application for its own work (the framework's default). */
    TASK
  }

  /** How a message group behaves when a delivery fails. */
  public enum Mode {
    /** No ordering. */
    IMMEDIATE,
    /** Continue with the next job of the group after a failure (the platform's default). */
    NEXT_ON_ERROR,
    /** Hold the group until the failed job succeeds. */
    BLOCK_ON_ERROR
  }

  /** The platform's dispatch queues. */
  public enum Queue {
    /** The normal queue. */
    DEFAULT,
    /** Delivered ahead of the normal queue. */
    HIGH_PRIORITY
  }

  /** The largest {@code VARCHAR} the platform's text columns hold. */
  static final int TEXT_MAX = 255;

  /** The most metadata pairs a job may carry. */
  static final int METADATA_MAX = 100;

  private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]*:\\S.*");
  private static final Pattern PLATFORM_ID = Pattern.compile("[0-9A-Za-z_-]{1,17}");

  /** Validates every field against the platform's rules. */
  public DispatchJob {
    if (code.length() > TEXT_MAX || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException(
          "dispatch job code must be {application}:{rest} (application [a-z][a-z0-9-]*), at most "
              + TEXT_MAX
              + " characters: "
              + code);
    }
    checkTarget(targetUrl);
    text("source", source);
    optionalText("subject", subject);
    optionalText("externalId", externalId);
    optionalText("messageGroup", messageGroup);
    optionalText("descriptor", descriptor);
    if (payloadContentType.isBlank() || payloadContentType.length() > 100) {
      throw new IllegalArgumentException("payloadContentType must be 1 to 100 characters");
    }
    platformId("id", id);
    platformId("eventId", eventId);
    platformId("platformClientId", platformClientId);
    platformId("subscriptionId", subscriptionId);
    platformId("serviceAccountId", serviceAccountId);
    platformId("dispatchPoolId", dispatchPoolId);
    positive("sequence", sequence);
    positive("timeoutSeconds", timeoutSeconds);
    positive("maxRetries", maxRetries);
    if (metadata.size() > METADATA_MAX) {
      throw new IllegalArgumentException("at most " + METADATA_MAX + " metadata entries");
    }
    for (Map.Entry<String, String> e : metadata.entrySet()) {
      if (e.getKey().isBlank()) {
        throw new IllegalArgumentException("metadata keys must not be blank");
      }
    }
    metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }

  /**
   * Starts a job.
   *
   * @param code {@code application:rest}, for example {@code orders:billing:export-invoice}
   * @param targetUrl where the platform delivers it
   * @return a builder with the defaults of section 7a
   */
  public static Builder builder(String code, URI targetUrl) {
    return new Builder(code, targetUrl);
  }

  private static void checkTarget(URI url) {
    String scheme = url.getScheme();
    if (!url.isAbsolute()
        || scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || url.getHost() == null
        || url.getHost().isEmpty()
        || url.getRawUserInfo() != null) {
      throw new IllegalArgumentException(
          "dispatch job targetUrl must be an absolute http(s) URL with a host and no user info");
    }
  }

  private static void text(String field, String value) {
    if (value.isBlank() || value.length() > TEXT_MAX) {
      throw new IllegalArgumentException(field + " must be 1 to " + TEXT_MAX + " characters");
    }
  }

  private static void optionalText(String field, @Nullable String value) {
    if (value != null) {
      text(field, value);
    }
  }

  private static void platformId(String field, @Nullable String value) {
    if (value != null && !PLATFORM_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must match [0-9A-Za-z_-]{1,17}");
    }
  }

  private static void positive(String field, @Nullable Integer value) {
    if (value != null && value < 1) {
      throw new IllegalArgumentException(
          field + " must be at least 1 (the platform turns 0 into its default)");
    }
  }

  /** Builds a {@link DispatchJob}; {@link #build()} validates. */
  public static final class Builder {
    private static final tools.jackson.databind.ObjectMapper MAPPER = Json.newMapper();

    private @Nullable String id;
    private Kind kind = Kind.TASK;
    private final String code;
    private @Nullable String source;
    private @Nullable String subject;
    private final URI targetUrl;
    private String payload = "{}";
    private String payloadContentType = "application/json";
    private boolean dataOnly;
    private @Nullable String externalId;
    private @Nullable String eventId;
    private @Nullable String platformClientId;
    private @Nullable String subscriptionId;
    private @Nullable String serviceAccountId;
    private @Nullable String dispatchPoolId;
    private @Nullable String messageGroup;
    private @Nullable Mode mode;
    private @Nullable Integer sequence;
    private @Nullable Integer timeoutSeconds;
    private @Nullable Integer maxRetries;
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private @Nullable Queue queue;
    private @Nullable String descriptor;

    private Builder(String code, URI targetUrl) {
      this.code = code;
      this.targetUrl = targetUrl;
    }

    /**
     * Sets the job id instead of letting the writer generate one, so the application knows it.
     *
     * @param value {@code [0-9A-Za-z_-]{1,17}}, unique on the platform
     * @return this builder
     */
    public Builder id(String value) {
      this.id = value;
      return this;
    }

    /**
     * Sets the kind; the default is {@link Kind#TASK}.
     *
     * @param value the kind
     * @return this builder
     */
    public Builder kind(Kind value) {
      this.kind = value;
      return this;
    }

    /**
     * Sets the source; the default is the code's application segment.
     *
     * @param value at most 255 characters
     * @return this builder
     */
    public Builder source(String value) {
      this.source = value;
      return this;
    }

    /**
     * Sets the subject.
     *
     * @param value at most 255 characters
     * @return this builder
     */
    public Builder subject(String value) {
      this.subject = value;
      return this;
    }

    /**
     * Sets the payload as text with its content type.
     *
     * @param text the body delivered
     * @param contentType its content type, at most 100 characters
     * @return this builder
     */
    public Builder payload(String text, String contentType) {
      this.payload = text;
      this.payloadContentType = contentType;
      return this;
    }

    /**
     * Sets the payload to a value serialised with the framework's Jackson mapper, content type
     * {@code application/json}.
     *
     * @param value a record, map, list or scalar
     * @return this builder
     */
    public Builder jsonPayload(Object value) {
      this.payload = MAPPER.writeValueAsString(value);
      this.payloadContentType = "application/json";
      return this;
    }

    /**
     * Delivers the payload alone instead of inside the platform's envelope.
     *
     * @param value true for the payload alone; the default is false
     * @return this builder
     */
    public Builder dataOnly(boolean value) {
      this.dataOnly = value;
      return this;
    }

    /**
     * Sets the application's own reference.
     *
     * @param value at most 255 characters
     * @return this builder
     */
    public Builder externalId(String value) {
      this.externalId = value;
      return this;
    }

    /**
     * Sets the platform id of a related event.
     *
     * @param value a platform id
     * @return this builder
     */
    public Builder eventId(String value) {
      this.eventId = value;
      return this;
    }

    /**
     * Sets the platform's id of the client the job belongs to (written as {@code clientId}). Not
     * the framework's {@code ClientId}, which is an identifier the platform would not resolve.
     *
     * @param value a platform id
     * @return this builder
     */
    public Builder platformClientId(String value) {
      this.platformClientId = value;
      return this;
    }

    /**
     * Sets the platform's subscription id.
     *
     * @param value a platform id
     * @return this builder
     */
    public Builder subscriptionId(String value) {
      this.subscriptionId = value;
      return this;
    }

    /**
     * Sets the platform's service account id.
     *
     * @param value a platform id
     * @return this builder
     */
    public Builder serviceAccountId(String value) {
      this.serviceAccountId = value;
      return this;
    }

    /**
     * Sets the platform's dispatch pool id.
     *
     * @param value a platform id
     * @return this builder
     */
    public Builder dispatchPoolId(String value) {
      this.dispatchPoolId = value;
      return this;
    }

    /**
     * Sets the ordering group; also the outbox row's {@code message_group}.
     *
     * @param value at most 255 characters
     * @return this builder
     */
    public Builder messageGroup(String value) {
      this.messageGroup = value;
      return this;
    }

    /**
     * Sets the group's delivery mode.
     *
     * @param value the mode
     * @return this builder
     */
    public Builder mode(Mode value) {
      this.mode = value;
      return this;
    }

    /**
     * Sets the order within the group.
     *
     * @param value 1 or more
     * @return this builder
     */
    public Builder sequence(int value) {
      this.sequence = value;
      return this;
    }

    /**
     * Sets the delivery timeout.
     *
     * @param seconds 1 or more
     * @return this builder
     */
    public Builder timeoutSeconds(int seconds) {
      this.timeoutSeconds = seconds;
      return this;
    }

    /**
     * Sets the number of retries.
     *
     * @param value 1 or more
     * @return this builder
     */
    public Builder maxRetries(int value) {
      this.maxRetries = value;
      return this;
    }

    /**
     * Adds a metadata pair; a second value for one key replaces the first.
     *
     * @param key not blank
     * @param value the value
     * @return this builder
     */
    public Builder metadata(String key, String value) {
      this.metadata.put(key, value);
      return this;
    }

    /**
     * Sets the platform queue.
     *
     * @param value the queue
     * @return this builder
     */
    public Builder queue(Queue value) {
      this.queue = value;
      return this;
    }

    /**
     * Sets a short label.
     *
     * @param value at most 255 characters
     * @return this builder
     */
    public Builder descriptor(String value) {
      this.descriptor = value;
      return this;
    }

    /**
     * Builds and validates the job.
     *
     * @return the job
     * @throws IllegalArgumentException when a value breaks a rule of section 7a
     */
    public DispatchJob build() {
      String src = source;
      if (src == null) {
        int colon = code.indexOf(':');
        src = colon > 0 ? code.substring(0, colon) : code;
      }
      return new DispatchJob(
          id,
          kind,
          code,
          src,
          subject,
          targetUrl,
          payload,
          payloadContentType,
          dataOnly,
          externalId,
          eventId,
          platformClientId,
          subscriptionId,
          serviceAccountId,
          dispatchPoolId,
          messageGroup,
          mode,
          sequence,
          timeoutSeconds,
          maxRetries,
          metadata,
          queue,
          descriptor);
    }
  }
}
