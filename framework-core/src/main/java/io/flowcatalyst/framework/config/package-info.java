/**
 * Declared settings, layered sources and secrets (core contract, section 4).
 *
 * <p>Every setting is declared once, in code, as a {@link io.flowcatalyst.framework.config.Setting}.
 * {@link io.flowcatalyst.framework.config.Config#load} resolves them against the sources in the
 * contract's order and reports every problem together.
 */
@NullMarked
package io.flowcatalyst.framework.config;

import org.jspecify.annotations.NullMarked;
