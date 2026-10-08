package io.flowcatalyst.framework.usecase;

record ThingEvent(EventMetadata metadata, String thingId, String name, String code)
    implements DomainEvent {}
