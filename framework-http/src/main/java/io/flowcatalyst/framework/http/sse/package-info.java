/**
 * Server-sent event streams with heartbeats, {@code Last-Event-ID} replay from the application's
 * own log, and an end on shutdown before the HTTP server drains ({@link
 * io.flowcatalyst.framework.http.sse.EventStreams}).
 */
@NullMarked
package io.flowcatalyst.framework.http.sse;

import org.jspecify.annotations.NullMarked;
