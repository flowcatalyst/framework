package io.flowcatalyst.framework.config;

/**
 * A module's claim on a configuration component (config-and-code contract, section 5): every key
 * under the component in a file must be declared by some module, or start-up fails.
 *
 * @param module the claiming module's id
 * @param component the first segment of the keys, for example {@code http}
 */
public record ComponentClaim(String module, String component) {}
