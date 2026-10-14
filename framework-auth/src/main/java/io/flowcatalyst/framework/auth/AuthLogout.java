package io.flowcatalyst.framework.auth;

import org.jspecify.annotations.Nullable;

/**
 * The logout answer (component {@code AuthLogout}, contract sections 4.4 and 4.5).
 *
 * @param loggedOut always true
 * @param endSessionUrl the platform's end-session URL, absent when discovery has none or cannot be
 *     read
 */
record AuthLogout(boolean loggedOut, @Nullable String endSessionUrl) {}
