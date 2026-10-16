package io.flowcatalyst.framework.context;

import java.util.regex.Pattern;

/**
 * Identifies a client (the tenant). Always present in a {@link Context}.
 *
 * <p><b>The name says "id"; the value is the client's code.</b> The platform has two names for a
 * client: a generated id ({@code clt_0HZ...}) and a code, also called its identifier ({@code acme}).
 * This type holds the <em>code</em>, which the framework writes as {@code clientCode} into outbox
 * rows and reads from the dispatch header and the token's {@code clients} entries. Neither the type
 * nor the framework can tell a {@code clt_...} id from a code, so build it from the code. See the
 * guide's "What goes in a ClientId" and its table of the three things called "client id".
 *
 * <p>The text never contains {@code :}, so a client id can be embedded in composite keys (lock
 * and cache keys) without two different clients producing the same key.
 *
 * @param value the identifier text, matching {@code [A-Za-z0-9._-]{1,100}}
 */
public record ClientId(String value) {
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

  /** Rejects values outside {@code [A-Za-z0-9._-]{1,100}}. */
  public ClientId {
    if (!VALID.matcher(value).matches()) {
      throw new IllegalArgumentException("ClientId must match [A-Za-z0-9._-]{1,100}: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
