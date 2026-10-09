/**
 * The FlowCatalyst module (flowcatalyst contract): the platform client built with client
 * credentials, its health check, definition sync, the {@code outbox} backlog health check, and
 * the receivers the platform delivers to ({@link io.flowcatalyst.framework.flowcatalyst.Receivers}).
 *
 * <p>This is the only framework module that imports the FlowCatalyst SDK; no SDK type appears in
 * a public signature.
 */
@NullMarked
package io.flowcatalyst.framework.flowcatalyst;

import org.jspecify.annotations.NullMarked;
