package io.flowcatalyst.framework.database;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/** One named database instance: a HikariCP pool over the PostgreSQL JDBC driver. */
public final class Database implements AutoCloseable {

  private final String name;
  private final HikariDataSource dataSource;
  private final boolean migrate;

  Database(String name, HikariDataSource dataSource, boolean migrate) {
    this.name = name;
    this.dataSource = dataSource;
    this.migrate = migrate;
  }

  /**
   * The instance name.
   *
   * @return the name used in configuration
   */
  public String name() {
    return name;
  }

  /**
   * The pooled data source. Connections must be closed to return them to the pool.
   *
   * @return the data source
   */
  public DataSource dataSource() {
    return dataSource;
  }

  /**
   * Whether migrations may run on this instance (when it is the default instance).
   *
   * @return the {@code migrate} option of {@code DatabaseModule}
   */
  public boolean migrates() {
    return migrate;
  }

  /**
   * Checks that the database answers.
   *
   * @throws SQLException when it does not
   */
  public void ping() throws SQLException {
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement()) {
      s.execute("SELECT 1");
    }
  }

  /**
   * Whether a table (or other relation) exists.
   *
   * @param qualifiedName the table name, optionally schema-qualified
   * @return true when it exists
   * @throws SQLException on a database error
   */
  public boolean tableExists(String qualifiedName) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement s = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      s.setString(1, qualifiedName);
      try (ResultSet rs = s.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  /**
   * Fails start-up unless a table exists. For modules whose code needs a table on a database
   * instance that is not the default one, where migrations do not run.
   *
   * @param qualifiedName the table name
   * @param needer a description of who needs it, for the message
   * @throws IllegalStateException when the table is missing, or the check fails
   */
  public void requireTable(String qualifiedName, String needer) {
    try {
      if (!tableExists(qualifiedName)) {
        throw new IllegalStateException(
            needer
                + " needs table '"
                + qualifiedName
                + "' in database instance '"
                + name
                + "', but it does not exist; migrations do not run on this instance, "
                + "so create the table yourself or use the default database instance");
      }
    } catch (SQLException e) {
      throw new IllegalStateException(
          "cannot check for table '" + qualifiedName + "' in database instance '" + name + "'", e);
    }
  }

  /** Closes the pool. */
  @Override
  public void close() {
    dataSource.close();
  }
}
