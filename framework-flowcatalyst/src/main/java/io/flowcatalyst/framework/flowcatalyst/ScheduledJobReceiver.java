package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.http.Handler;
import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST {prefix}/scheduled-jobs}: the platform firing a scheduled job (flowcatalyst
 * contract, section 4). Authenticates (401), validates the envelope as the TypeScript runner does
 * (400), finds the handler (404), resolves the application's service principal (503 when it
 * cannot, so the platform redelivers; section 4b), starts the run in the background and answers
 * {@code 202 {"ok":true}} at once: the platform's HTTP timeout is 10 s.
 */
final class ScheduledJobReceiver implements Handler {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledJobReceiver.class);

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  private final Receivers receivers;
  private final ReceiverGate gate;
  private final ScheduledJobRunner runner;
  private final PlatformClient platform;
  private final Supplier<PrincipalId> servicePrincipal;
  private final String route;

  ScheduledJobReceiver(
      Receivers receivers,
      ReceiverGate gate,
      ScheduledJobRunner runner,
      PlatformClient platform,
      Supplier<PrincipalId> servicePrincipal,
      String route) {
    this.receivers = receivers;
    this.gate = gate;
    this.runner = runner;
    this.platform = platform;
    this.servicePrincipal = servicePrincipal;
    this.route = route;
  }

  @Override
  public Response handle(Request request) {
    if (!receivers.isReady()) {
      return DispatchReceiver.unavailable("the receivers are not ready");
    }
    if (!receivers.hasJobHandlers()) {
      return HttpErrors.notFound("no route for this path");
    }
    Optional<Response> refused = gate.refuse(request, route);
    if (refused.isPresent()) {
      return refused.get();
    }
    ScheduledJobFiring firing;
    try {
      firing = parse(request.bodyBytes(), platform);
    } catch (MalformedEnvelope e) {
      return HttpErrors.validation("the scheduled-job envelope is malformed: " + e.getMessage());
    }
    Optional<Receivers.JobRegistration> handler = receivers.jobHandler(firing.jobCode());
    if (handler.isEmpty()) {
      LOG.warn(
          "flowcatalyst: no handler for scheduled job {} (instance {})",
          firing.jobCode(),
          firing.instanceId());
      return HttpErrors.notFound("no handler for scheduled job " + firing.jobCode());
    }
    // Before the 202: a firing that cannot run as the service principal is redelivered.
    PrincipalId principal;
    try {
      principal = servicePrincipal.get();
    } catch (RuntimeException e) {
      LOG.error(
          "flowcatalyst: scheduled job {} instance {} was not run: {} ({}); answered 503, the"
              + " platform redelivers",
          firing.jobCode(),
          firing.instanceId(),
          DispatchReceiver.NO_PRINCIPAL,
          e.getMessage());
      return DispatchReceiver.unavailable(DispatchReceiver.NO_PRINCIPAL);
    }
    if (!runner.start(firing, handler.get(), principal)) {
      return DispatchReceiver.unavailable("the application is stopping");
    }
    return Response.json(202, Map.of("ok", true));
  }

  /**
   * Validates an envelope: string {@code jobId}, {@code jobCode}, {@code instanceId}, {@code
   * firedAt} and {@code triggerKind} ({@code CRON|MANUAL}), boolean {@code tracksCompletion}, and
   * the optional fields' types. Absent {@code concurrent} means false (lock), the safe direction.
   */
  static ScheduledJobFiring parse(byte[] body, @Nullable PlatformClient platform) throws MalformedEnvelope {
    JsonNode root = Envelopes.readTree(body, true);
    if (root == null || !root.isObject()) {
      throw new MalformedEnvelope("the body must be one JSON object with unique keys");
    }
    String jobId = required(root, "jobId", ID);
    String jobCode = required(root, "jobCode", Receivers.JOB_CODE);
    String instanceId = required(root, "instanceId", ID);
    Instant firedAt = toInstant(required(root, "firedAt", null), "firedAt");
    String trigger = required(root, "triggerKind", null);
    TriggerKind triggerKind =
        switch (trigger) {
          case "CRON" -> TriggerKind.CRON;
          case "MANUAL" -> TriggerKind.MANUAL;
          default -> throw new MalformedEnvelope("triggerKind must be CRON or MANUAL");
        };
    JsonNode tracks = root.get("tracksCompletion");
    if (tracks == null || !tracks.isBoolean()) {
      throw new MalformedEnvelope("tracksCompletion must be a boolean");
    }
    JsonNode concurrent = root.get("concurrent");
    if (concurrent != null && !concurrent.isNull() && !concurrent.isBoolean()) {
      throw new MalformedEnvelope("concurrent must be a boolean");
    }
    Duration timeout = null;
    JsonNode timeoutSeconds = root.get("timeoutSeconds");
    if (timeoutSeconds != null && !timeoutSeconds.isNull()) {
      if (!timeoutSeconds.isIntegralNumber()
          || !timeoutSeconds.canConvertToInt()
          || timeoutSeconds.asInt() < 1) {
        throw new MalformedEnvelope("timeoutSeconds must be a positive integer");
      }
      timeout = Duration.ofSeconds(timeoutSeconds.asInt());
    }
    String scheduled = optionalString(root, "scheduledFor");
    Instant scheduledFor = scheduled == null ? null : toInstant(scheduled, "scheduledFor");
    String correlation = optionalString(root, "correlationId");
    CorrelationId correlationId =
        correlation != null && DispatchReceiver.CORRELATION.matcher(correlation).matches()
            ? new CorrelationId(correlation)
            : CorrelationId.generate();
    JsonNode payload = root.get("payload");
    return new ScheduledJobFiring(
        jobId,
        jobCode,
        instanceId,
        scheduledFor,
        firedAt,
        triggerKind,
        correlationId,
        payload == null || payload.isNull() ? null : payload,
        tracks.asBoolean(),
        timeout,
        concurrent != null && concurrent.asBoolean(),
        platform);
  }

  private static String required(JsonNode root, String field, @Nullable Pattern pattern)
      throws MalformedEnvelope {
    JsonNode node = root.get(field);
    if (node == null || !node.isString()) {
      throw new MalformedEnvelope(field + " must be a string");
    }
    String value = node.asString();
    if (pattern != null && !pattern.matcher(value).matches()) {
      throw new MalformedEnvelope(field + " is not well formed");
    }
    return value;
  }

  private static @Nullable String optionalString(JsonNode root, String field) throws MalformedEnvelope {
    JsonNode node = root.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      throw new MalformedEnvelope(field + " must be a string");
    }
    return node.asString();
  }

  private static Instant toInstant(String text, String field) throws MalformedEnvelope {
    if (text.length() > 64) {
      throw new MalformedEnvelope(field + " must be an RFC 3339 time");
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      try {
        return OffsetDateTime.parse(text).toInstant();
      } catch (DateTimeParseException e2) {
        throw new MalformedEnvelope(field + " must be an RFC 3339 time");
      }
    }
  }
}
