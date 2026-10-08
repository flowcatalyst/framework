/**
 * Modules, the application lifecycle, named instances and drivers, and health checks (core
 * contract, sections 1 and 2).
 *
 * <p>Wiring is done in code: modules are listed on the builder, services are provided as
 * functions from a typed lookup, and everything is built and validated before
 * {@link io.flowcatalyst.framework.core.AppBuilder#start()} returns.
 */
@NullMarked
package io.flowcatalyst.framework.core;

import org.jspecify.annotations.NullMarked;
