package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.flowcatalyst.DispatchDelivery;
import io.flowcatalyst.framework.flowcatalyst.DispatchHandler;
import io.flowcatalyst.framework.flowcatalyst.DispatchResult;
import io.flowcatalyst.framework.httpclient.ClientResponse;
import io.flowcatalyst.framework.httpclient.ServiceClient;
import io.flowcatalyst.framework.httpclient.module.ServiceClients;
import io.flowcatalyst.framework.secrets.SecretRef;
import io.flowcatalyst.framework.secrets.SecretResolver;
import io.flowcatalyst.framework.secrets.SecretResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The platform delivers {@code tickets-trial:support:ticket:assigned} here (through the
 * {@link TicketsDefinitionsModule#NOTIFY_SUBSCRIPTION} subscription). The handler tells the new
 * assignee through the notification service, as the delivery's client: the credential is that
 * client's own key, resolved by {@link SecretResolver}, never one shared by every client.
 *
 * <p>A delivery can arrive more than once, so the request carries the job id as its idempotency key
 * and the notification service drops a repeat. {@code DispatchDelivery.once} would not help: it
 * makes a database write and its record atomic, and an HTTP call is not part of that transaction.
 *
 * <p>What each outcome answers to the platform:
 *
 * <ul>
 *   <li>sent, or the assignee assigned the ticket to themselves: {@code ack};
 *   <li>the client has no notification key, or the service refused the request (a 4xx other than
 *       429): {@code reject}, which is terminal and shows on the dispatch job (the reason names no
 *       secret);
 *   <li>the service is unavailable (429, 5xx, no answer): the handler throws, which is a transient
 *       error the platform retries with its backoff and within the job's retry budget.
 * </ul>
 */
public final class NotifyAssignee implements DispatchHandler {

  private static final Logger LOG = LoggerFactory.getLogger(NotifyAssignee.class);

  /** The reference of a client's key for the notification service (the {@code env} store). */
  static final SecretRef KEY = SecretRef.parse("notify-key");

  /** The named HTTP client; its base URL is {@code http-clients.instances.notifier.base-url}. */
  public static final String CLIENT = "notifier";

  private final ServiceClients clients;
  private final SecretResolver secrets;

  /**
   * Creates the handler.
   *
   * @param clients the named HTTP clients
   * @param secrets resolves a client's secret
   */
  public NotifyAssignee(ServiceClients clients, SecretResolver secrets) {
    this.clients = clients;
    this.secrets = secrets;
  }

  @Override
  public DispatchResult handle(Context ctx, DispatchDelivery delivery) {
    JsonNode data = delivery.data().orElse(null);
    if (data == null || !data.hasNonNull("assigneeId") || !data.hasNonNull("ticketId")) {
      return DispatchResult.reject("the delivery has no assignee or ticket");
    }
    String assignee = data.get("assigneeId").asString();
    String ticket = data.get("ticketId").asString();
    String assignedBy = data.hasNonNull("assignedBy") ? data.get("assignedBy").asString() : "";
    if (assignee.equals(assignedBy)) {
      return DispatchResult.ack(); // assigning a ticket to yourself tells nobody anything
    }
    SecretResult key = secrets.resolve(ctx.client(), KEY);
    if (!(key instanceof SecretResult.Found found)) {
      LOG.warn("client {} has no notification key; job {} rejected", ctx.client(), delivery.jobId());
      return DispatchResult.reject("this client has no notification key");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("to", assignee);
    body.put("ticketId", ticket);
    body.put("subject", data.hasNonNull("subject") ? data.get("subject").asString() : "");
    body.put("assignedBy", assignedBy);
    ServiceClient notifier = clients.get(CLIENT, ctx);
    ClientResponse response =
        notifier
            .post("/notifications")
            .header("Authorization", "Bearer " + found.secret().reveal())
            .idempotencyKey(delivery.jobId())
            .json(body)
            .send();
    if (response.isSuccess()) {
      return DispatchResult.ack();
    }
    if (response.status() >= 400 && response.status() < 500 && response.status() != 429) {
      return DispatchResult.reject("the notification service refused the request (" + response.status() + ")");
    }
    throw new IllegalStateException("the notification service answered " + response.status());
  }
}
