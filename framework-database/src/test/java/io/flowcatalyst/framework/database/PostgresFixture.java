package io.flowcatalyst.framework.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** A real PostgreSQL 18 for one test class. Start it in {@code @BeforeAll}, close in {@code @AfterAll}. */
public final class PostgresFixture implements AutoCloseable {

  private final PostgreSQLContainer container;

  private PostgresFixture(PostgreSQLContainer container) {
    this.container = container;
  }

  /** Starts a container. */
  public static PostgresFixture start() {
    PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    container.start();
    return new PostgresFixture(container);
  }

  /** JDBC URL of the default database. */
  public String jdbcUrl() {
    return container.getJdbcUrl();
  }

  /** JDBC URL of another database on the same server. */
  public String jdbcUrl(String database) {
    return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + database;
  }

  /** The user name. */
  public String username() {
    return container.getUsername();
  }

  /** The password. */
  public String password() {
    return container.getPassword();
  }

  /** Runs SQL as the superuser on the default database. */
  public void execute(String sql) {
    execute(jdbcUrl(), sql);
  }

  /** Runs SQL as the superuser on the named database. */
  public void execute(String url, String sql) {
    try (Connection c = DriverManager.getConnection(url, username(), password());
        Statement s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Creates a database on the server. */
  public void createDatabase(String name) {
    execute("CREATE DATABASE " + name);
  }

  /** Empties the default database's public schema, so the next test starts clean. */
  public void reset() {
    execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
  }

  /** Reads a single value. */
  public String query(String sql) {
    try (Connection c = DriverManager.getConnection(jdbcUrl(), username(), password());
        Statement s = c.createStatement();
        java.sql.ResultSet rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    container.stop();
  }
}
