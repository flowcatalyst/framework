package io.flowcatalyst.framework.database;

import io.flowcatalyst.framework.core.StartupException;
import java.util.List;
import javax.sql.DataSource;

/**
 * Applies migration sets to a database without starting an application. This is the same runner
 * {@link DatabaseModule} uses at start-up (database contract, section 2); build tools use it to
 * migrate a throwaway database.
 */
public final class Migrations {

  private Migrations() {}

  /**
   * Applies the sets in order, each file once, recording the history.
   *
   * @param databaseName the name shown in error messages
   * @param dataSource the database to migrate
   * @param sets the sets, applied in the order given (by version within a set)
   * @throws StartupException naming the module, file and database error
   */
  public static void apply(String databaseName, DataSource dataSource, List<MigrationSet> sets) {
    new MigrationRunner(databaseName, dataSource, sets).run();
  }
}
