package io.flowcatalyst.framework.usecase;

import java.sql.Connection;

/**
 * The transaction of one attempt, handed to repositories. Application code never begins,
 * commits or rolls it back: the runner does.
 */
public final class Tx {

  private final Connection connection;

  Tx(Connection connection) {
    this.connection = connection;
  }

  /**
   * The connection the attempt's transaction runs on. Do not close it or change its
   * transaction settings.
   *
   * @return the connection
   */
  public Connection connection() {
    return connection;
  }
}
