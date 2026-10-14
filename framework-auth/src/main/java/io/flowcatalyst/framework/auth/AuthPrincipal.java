package io.flowcatalyst.framework.auth;

import org.jspecify.annotations.Nullable;

/**
 * The principal in the session answer (component {@code AuthPrincipal}, contract section 4.5).
 *
 * @param id the platform's principal id
 * @param kind {@code USER} or {@code SERVICE}
 * @param name the display name, absent when the id_token had none
 * @param email the email address, absent when the id_token had none
 */
record AuthPrincipal(String id, PrincipalKind kind, @Nullable String name, @Nullable String email) {}
