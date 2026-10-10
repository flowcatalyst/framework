/**
 * Per-client secrets an application references (secrets contract): {@link
 * io.flowcatalyst.framework.secrets.SecretResolver} resolves a {@link
 * io.flowcatalyst.framework.secrets.SecretRef} for the client the caller acts for, through named
 * store instances with the {@code env} and {@code file} drivers. Values never appear in logs,
 * messages or results other than inside a {@link io.flowcatalyst.framework.config.Secret}.
 */
@NullMarked
package io.flowcatalyst.framework.secrets;

import org.jspecify.annotations.NullMarked;
