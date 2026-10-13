/**
 * The {@code database} session store, the default (sessions-and-login contract section 6.3):
 * {@link io.flowcatalyst.framework.auth.database.AuthDatabaseModule} contributes the {@code
 * fw_session} migration, provides the {@code SessionStore} and purges expired rows.
 */
@NullMarked
package io.flowcatalyst.framework.auth.database;

import org.jspecify.annotations.NullMarked;
