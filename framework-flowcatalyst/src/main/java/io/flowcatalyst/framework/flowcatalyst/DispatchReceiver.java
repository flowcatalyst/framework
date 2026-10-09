package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.http.Handler;
import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST {prefix}/dispatch}: the platform delivering a dispatch job (flowcatalyst contract,
 * section 4).
 *
 * <ol>
 *   <li>Authenticate ({@link ReceiverGate}); refused: 401.
 *   <li>Validate the job headers and, for an envelope, its fields; malformed: 400. Nothing
 *       malformed reaches a handler.
 *   <li>Find the handler; none: 422 reject.
 *   <li>Run it on its own virtual thread, bounded by {@code dispatch-timeout}; a throw or a
 *       timeout (the thread is interrupted) is 503, the platform retries.
 *   <li>Map the result: ack 200, defer 200 with {@code ack:false}, reject 422 with
 *       {@code FlowCatalyst-Outcome: reject}.
 * </ol>
 */
final class DispatchReceiver implements Handler {

  private static final Logger LOG = LoggerFactory.getLogger(DispatchReceiver.class);

  static final String JOB_ID_HEADER = "X-Dispatch-Job-Id";
  static final String EVENT_TYPE_HEADER = "X-Event-Type";
  static final String CLIENT_HEADER = "X-FlowCatalyst-Client";
  static final String OUTCOME_HEADER = "FlowCatalyst-Outcome";

  private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern EVENT_TYPE = Pattern.compile("[A-Za-z0-9._:-]{1,255}");
  private static final Pattern CLIENT = Pattern.compile("([A-Za-z0-9._-]{1,100}):([A-Za-z0-9._-]{1,100})");
  /** Printable ASCII without spaces: safe in a log line and a context. */
  static final Pattern CORRELATION = Pattern.compile("[\\x21-\\x7E]{1,200}");

  private final Receivers receivers;
  private final ReceiverGate gate;
  private final ApplicationCode application;
  private final Duration timeout;
  private final String route;

  DispatchReceiver(
      Receivers receivers,
      ReceiverGate gate,
      ApplicationCode application,
      Duration timeout,
      String route) {
    this.receivers = receivers;
    this.gate = gate;
    this.application = application;
    this.timeout = timeout;
    this.route = route;
  }

  @Override
  public Response handle(Request request) {
    if (!receivers.isReady()) {
      return unavailable("the receivers are not ready");
    }
    Optional<Response> refused = gate.refuse(request, route);
    if (refused.isPresent()) {
      return refused.get();
    }
    String jobId = single(request, JOB_ID_HEADER, JOB_ID);
    String type = single(request, EVENT_TYPE_HEADER, EVENT_TYPE);
    if (jobId == null || type == null) {
      return HttpErrors.validation(
          "the " + JOB_ID_HEADER + " and " + EVENT_TYPE_HEADER + " headers are required and must be well formed");
    }
    DispatchDelivery.Client client = null;
    List<String> clientValues = request.headers(CLIENT_HEADER);
    if (!clientValues.isEmpty()) {
      Matcher m = clientValues.size() == 1 ? CLIENT.matcher(clientValues.getFirst()) : null;
      if (m == null || !m.matches()) {
        return HttpErrors.validation("the " + CLIENT_HEADER + " header must be {id}:{code}");
      }
      client = new DispatchDelivery.Client(m.group(1), new ClientId(m.group(2)));
    }

    // The same cached bytes the gate verified (or, unsigned, read now): never re-read.
    byte[] body = request.bodyBytes();
    DispatchDelivery delivery;
    try {
      delivery = parse(jobId, type, client, body);
    } catch (MalformedEnvelope e) {
      return HttpErrors.validation("the dispatch envelope is malformed: " + e.getMessage());
    }

    Optional<DispatchHandler> handler = receivers.dispatchHandler(type);
    if (handler.isEmpty()) {
      LOG.warn("flowcatalyst: no dispatch handler for {} (job {}); rejected", type, jobId);
      return reject(new DispatchResult.Reject("no handler for " + type));
    }
    Context ctx =
        new Context(
            client == null ? Receivers.PLATFORM_CLIENT : client.code(),
            null,
            application,
            delivery.correlationId(),
            null,
            jobId);
    return run(handler.get(), ctx, delivery);
  }

  private Response run(DispatchHandler handler, Context ctx, DispatchDelivery delivery) {
    String jobId = delivery.jobId();
    FutureTask<DispatchResult> task = new FutureTask<>(() -> handler.handle(ctx, delivery));
    Thread worker = Thread.ofVirtual().name("fc-dispatch-" + jobId).unstarted(task);
    worker.start();
    DispatchResult result;
    try {
      result = task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      worker.interrupt();
      LOG.error(
          "flowcatalyst: dispatch handler for {} did not finish within {} (job {}, correlation {});"
              + " interrupted it and answered 503, the platform retries",
          delivery.eventType(),
          timeout,
          jobId,
          ctx.correlationId());
      return unavailable("the handler did not finish in time");
    } catch (ExecutionException e) {
      LOG.error(
          "flowcatalyst: dispatch handler for {} failed (job {}, correlation {}); answered 503,"
              + " the platform retries",
          delivery.eventType(),
          jobId,
          ctx.correlationId(),
          e.getCause());
      return unavailable("the handler failed");
    } catch (InterruptedException e) {
      worker.interrupt();
      Thread.currentThread().interrupt();
      return unavailable("the receiver was interrupted");
    }
    return switch (result) {
      case DispatchResult.Ack ack -> Response.json(200, Map.of("ack", true));
      case DispatchResult.Defer defer -> {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ack", false);
        body.put("delaySeconds", defer.delaySeconds());
        yield Response.json(200, body);
      }
      case DispatchResult.Reject r -> reject(r);
      case null -> {
        LOG.error(
            "flowcatalyst: dispatch handler for {} returned null (job {}, correlation {}); answered 503",
            delivery.eventType(),
            jobId,
            ctx.correlationId());
        yield unavailable("the handler failed");
      }
    };
  }

  private static Response reject(DispatchResult.Reject reject) {
    return HttpErrors.error(422, "REJECTED", reject.reason()).header(OUTCOME_HEADER, "reject");
  }

  static Response unavailable(String message) {
    return HttpErrors.error(503, "UNAVAILABLE", message);
  }

  /** The header's only value when it matches, else null (absent, repeated or malformed). */
  private static @Nullable String single(Request request, String name, Pattern pattern) {
    List<String> values = request.headers(name);
    if (values.size() != 1 || !pattern.matcher(values.getFirst()).matches()) {
      return null;
    }
    return values.getFirst();
  }

  static DispatchDelivery parse(
      String jobId, String type, DispatchDelivery.@Nullable Client client, byte[] body)
      throws MalformedEnvelope {
    // Recognise the envelope leniently, then require it to be unambiguous: a body shaped like an
    // envelope with duplicate keys is malformed, never a data-only payload.
    JsonNode shape = Envelopes.readTree(body, false);
    if (shape == null
        || !shape.isObject()
        || !jobId.equals(Envelopes.text(shape, "id"))
        || !type.equals(Envelopes.text(shape, "type"))) {
      return new DispatchDelivery(
          jobId,
          type,
          null,
          null,
          null,
          null,
          CorrelationId.generate(),
          client,
          true,
          Envelopes.readTree(body, true),
          body);
    }
    JsonNode root = Envelopes.readTree(body, true);
    if (root == null) {
      throw new MalformedEnvelope("it has duplicate keys");
    }
    JsonNode attempt = root.get("attemptNumber");
    if (attempt == null || !attempt.isIntegralNumber() || !attempt.canConvertToInt() || attempt.asInt() < 1) {
      throw new MalformedEnvelope("attemptNumber must be a positive integer");
    }
    String source = optionalString(root, "source");
    String subject = optionalString(root, "subject");
    String messageGroup = optionalString(root, "messageGroup");
    String correlation = optionalString(root, "correlationId");
    optionalString(root, "clientId");
    optionalString(root, "clientCode");
    CorrelationId correlationId =
        correlation != null && CORRELATION.matcher(correlation).matches()
            ? new CorrelationId(correlation)
            : CorrelationId.generate();
    JsonNode data = root.get("data");
    return new DispatchDelivery(
        jobId,
        type,
        attempt.asInt(),
        source,
        subject,
        messageGroup,
        correlationId,
        client,
        false,
        data,
        body);
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
}
