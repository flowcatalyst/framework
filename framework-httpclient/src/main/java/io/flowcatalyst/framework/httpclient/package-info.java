/**
 * A blocking HTTP client for virtual threads over Vert.x (http-client contract): {@link
 * io.flowcatalyst.framework.httpclient.HttpClients} owns the engine, {@link
 * io.flowcatalyst.framework.httpclient.ServiceClient} is one consumed service with its base URL,
 * timeouts, auth and retry policy. Standalone: it does not depend on {@code framework-core}.
 */
@NullMarked
package io.flowcatalyst.framework.httpclient;

import org.jspecify.annotations.NullMarked;
