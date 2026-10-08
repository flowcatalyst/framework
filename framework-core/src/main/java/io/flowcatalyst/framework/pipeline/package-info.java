/**
 * The one middleware abstraction, shared by HTTP, jobs and commands (HTTP contract section 4): a
 * {@link io.flowcatalyst.framework.pipeline.Middleware} wraps the rest of the chain, and a
 * {@link io.flowcatalyst.framework.pipeline.Pipeline} composes a list of them around a terminal
 * handler.
 */
@NullMarked
package io.flowcatalyst.framework.pipeline;

import org.jspecify.annotations.NullMarked;
