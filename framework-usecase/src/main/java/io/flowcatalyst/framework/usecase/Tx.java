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
   * Wraps a connection that is already in a transaction, to run a use case inside it
   * ({@link UseCases#run(UseCase, io.flowcatalyst.framework.context.Context, Object, Tx)}). The
   * caller keeps the transaction: the runner never commits or rolls it back.
   *
   * @param connection a connection with auto-commit off
   * @return the transaction handle
   */
  public static Tx of(Connection connection) {
    return new Tx(connection);
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
