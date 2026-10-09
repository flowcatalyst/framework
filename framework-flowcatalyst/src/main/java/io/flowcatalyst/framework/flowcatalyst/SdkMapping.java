package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.sdk.sync.Definitions;
import io.flowcatalyst.sdk.sync.Definitions.DefinitionSet;
import io.flowcatalyst.sdk.sync.Definitions.PermissionRef;
import java.util.List;

/** Maps the framework's definition types to the SDK's. The only place SDK definition types are named. */
final class SdkMapping {

  private SdkMapping() {}

  static DefinitionSet toSet(String application, DefinitionCatalog catalog) {
    return DefinitionSet.define(application)
        .withRoles(catalog.roles().stream().map(SdkMapping::role).toList())
        .withEventTypes(
            catalog.eventTypes().stream()
                .map(e -> new Definitions.EventType(e.code(), e.name(), e.description()))
                .toList())
        .withConnections(
            catalog.connections().stream()
                .map(
                    c ->
                        new Definitions.Connection(
                            c.code(), c.name(), c.description(), null, null))
                .toList())
        .withSubscriptions(catalog.subscriptions().stream().map(SdkMapping::subscription).toList())
        .withDispatchPools(
            catalog.dispatchPools().stream()
                .map(
                    p ->
                        new Definitions.DispatchPool(
                            p.code(), p.name(), p.description(), p.rateLimit(), p.concurrency()))
                .toList())
        .withProcesses(
            catalog.processes().stream()
                .map(
                    p ->
                        new Definitions.Process(
                            p.code(), p.name(), p.description(), p.body(), p.diagramType(), p.tags()))
                .toList())
        .withScheduledJobs(catalog.scheduledJobs().stream().map(SdkMapping::scheduledJob).toList());
  }

  private static Definitions.Role role(Definition.Role r) {
    List<PermissionRef> permissions = r.permissions().stream().map(PermissionRef::raw).toList();
    return new Definitions.Role(r.name(), r.displayName(), r.description(), permissions, null);
  }

  private static Definitions.Subscription subscription(Definition.Subscription s) {
    List<Definitions.SubscriptionEventType> eventTypes =
        s.eventTypes().stream().map(Definitions.SubscriptionEventType::of).toList();
    Definition.DeliveryMode mode = s.mode();
    return new Definitions.Subscription(
        s.code(),
        s.name(),
        s.description(),
        s.target(),
        null,
        eventTypes,
        s.dispatchPoolCode(),
        mode == null ? null : Definitions.SubscriptionMode.valueOf(mode.name()),
        s.maxRetries(),
        s.timeoutSeconds(),
        s.dataOnly(),
        s.connectionCode(),
        s.sharedConnection() ? Boolean.TRUE : null,
        null);
  }

  private static Definitions.ScheduledJob scheduledJob(Definition.ScheduledJob j) {
    return new Definitions.ScheduledJob(
        j.code(),
        j.name(),
        j.description(),
        j.crons(),
        j.timezone(),
        j.payload(),
        j.concurrent(),
        j.tracksCompletion(),
        j.timeoutSeconds(),
        j.deliveryMaxAttempts(),
        j.targetUrl(),
        null);
  }
}
