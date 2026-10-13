/**
 * Sessions and login (M4c): the application is its own backend-for-frontend and an OIDC relying
 * party of the FlowCatalyst platform. {@link io.flowcatalyst.framework.auth.AuthModule} adds
 * {@code /auth/login}, {@code /auth/callback}, {@code /auth/session} and {@code /auth/logout}, and
 * provides the {@link io.flowcatalyst.framework.http.ContextResolver} that builds the {@code
 * Context} from the session cookie or a platform bearer token. {@link
 * io.flowcatalyst.framework.auth.PlatformTokens} verifies platform access tokens on its own.
 * Specification: {@code docs/contracts/sessions-and-login.md}.
 */
@NullMarked
package io.flowcatalyst.framework.auth;

import org.jspecify.annotations.NullMarked;
