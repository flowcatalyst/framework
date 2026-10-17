package io.flowcatalyst.framework.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.framework.config.InstanceConfig;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Instances;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides {@link Databases}: the instances configured under {@code database}, then applies the
 * migration sets contributed by modules to the default instance.
 *
 * <pre>{@code
 * database:
 *   default: main
 *   instances:
 *     main:
 *       url: jdbc:postgresql://localhost:5432/app
 *       username: app
 *       password: ""
 *       pool: { max: 10, min-idle: 1, connect-timeout: 5s, login-timeout: 10s }
 *       migrate: true
 * }</pre>
 *
 * <p>An instance is a connection, so all of it is configuration (config-and-code contract, section
 * 3.2). The pool keys are checked at start-up: {@code pool.max} 1 to 1000, {@code pool.min-idle} 0
 * to {@code pool.max}, {@code pool.connect-timeout} 250 ms to 5 minutes, {@code pool.login-timeout}
 * 1 second to 5 minutes in whole seconds. {@code connect-timeout} is how long a caller waits for a
 * connection from the pool; {@code login-timeout} (default 10s) is how long one attempt to open a
 * connection may take inside the driver, TCP connect, TLS and authentication, so a server that
 * accepts and never answers cannot hold a pool thread for ever. {@code migrate} counts on
 * the default instance, where migrations run. PostgreSQL is the only database; an instance names no
 * {@code driver}. Each instance's health check is {@code database:<name>}.
 */
public final class DatabaseModule implements Module {

  /** The module id. */
  public static final String ID = "database";

  /** The largest {@code pool.max} accepted. */
  static final int MAX_POOL = 1000;

  /** The shortest {@code pool.connect-timeout} accepted (HikariCP's own minimum). */
  static final Duration MIN_CONNECT_TIMEOUT = Duration.ofMillis(250);

  /** The longest {@code pool.connect-timeout} accepted. */
  static final Duration MAX_CONNECT_TIMEOUT = Duration.ofMinutes(5);

  /** The shortest {@code pool.login-timeout} accepted (the JDBC driver counts whole seconds). */
  static final Duration MIN_LOGIN_TIMEOUT = Duration.ofSeconds(1);

  /** The longest {@code pool.login-timeout} accepted. */
  static final Duration MAX_LOGIN_TIMEOUT = Duration.ofMinutes(5);

  /** The default {@code pool.login-timeout}. */
  static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofSeconds(10);

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.provide(
        Databases.class,
        lookup -> {
          Instances<Database> instances =
              Instances.build(
                  Database.class,
                  "database",
                  lookup,
                  (name, section, l, built) -> createInstance(name, section));
          try {
            migrate(instances, lookup.contributions(MigrationSet.class));
          } catch (StartupException e) {
            instances.close();
            throw e;
          }
          return new Databases(instances);
        });
  }

  @Override
  public void boot(App app) {
    Databases databases = app.get(Databases.class);
    for (String name : databases.names()) {
      Database db = databases.get(name);
      app.health().add("database:" + name, db::ping);
    }
  }

  private static void migrate(Instances<Database> instances, List<MigrationSet> sets) {
    if (sets.isEmpty() || instances.names().isEmpty()) {
      return;
    }
    Database target = instances.defaultInstance();
    if (!target.migrates()) {
      return;
    }
    Migrations.apply(target.name(), target.dataSource(), sets);
  }

  private static Database createInstance(String name, InstanceConfig config) {
    String url = config.string("url");
    String username = config.string("username", "");
    Secret password = config.findSecret("password").orElse(Secret.of(""));
    InstanceConfig pool = config.section("pool");
    int max = pool.integer("max", 10);
    int minIdle = pool.integer("min-idle", 1);
    Duration connectTimeout = pool.duration("connect-timeout", Duration.ofSeconds(5));
    Duration loginTimeout = pool.duration("login-timeout", DEFAULT_LOGIN_TIMEOUT);
    boolean migrate = config.bool("migrate", true);

    // Checked before any connection is opened; every problem of the instance at once.
    List<Problem> problems = new ArrayList<>();
    boolean maxValid = max >= 1 && max <= MAX_POOL;
    if (!maxValid) {
      problems.add(outOfRange(pool, "max", "must be 1 to 1000, was " + max));
    }
    // against pool.max only when that is valid, so one wrong value is one problem
    if (minIdle < 0 || (maxValid && minIdle > max)) {
      problems.add(outOfRange(pool, "min-idle", "must be 0 to pool.max (" + max + "), was " + minIdle));
    }
    if (connectTimeout.compareTo(MIN_CONNECT_TIMEOUT) < 0
        || connectTimeout.compareTo(MAX_CONNECT_TIMEOUT) > 0) {
      problems.add(outOfRange(pool, "connect-timeout", "must be 250ms to 5m, was " + connectTimeout));
    }
    if (loginTimeout.compareTo(MIN_LOGIN_TIMEOUT) < 0
        || loginTimeout.compareTo(MAX_LOGIN_TIMEOUT) > 0
        || loginTimeout.toMillis() % 1000 != 0) {
      problems.add(
          outOfRange(pool, "login-timeout", "must be 1s to 5m in whole seconds, was " + loginTimeout));
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }

    HikariConfig hikari = new HikariConfig();
    hikari.setPoolName("fw-db-" + name);
    hikari.setJdbcUrl(url);
    hikari.setUsername(username);
    hikari.setPassword(password.reveal());
    hikari.setMaximumPoolSize(max);
    hikari.setMinimumIdle(minIdle);
    hikari.setConnectionTimeout(connectTimeout.toMillis());
    // The driver's own limit for one attempt (pgjdbc "loginTimeout", seconds): without it a server
    // that accepts the connection and never answers holds the attempt for ever.
    hikari.addDataSourceProperty("loginTimeout", String.valueOf(loginTimeout.toSeconds()));
    // Fail in the check below, with the instance's name, rather than inside the pool constructor.
    hikari.setInitializationFailTimeout(-1);

    HikariDataSource pooled = new HikariDataSource(hikari);
    Database db = new Database(name, pooled, migrate);
    try {
      db.ping();
    } catch (SQLException | RuntimeException e) {
      pooled.close();
      throw new IllegalStateException(
          "cannot connect to database instance '"
              + name
              + "': "
              + e.getMessage()
              + (timedOut(e)
                  ? " (limits: pool.connect-timeout "
                      + connectTimeout
                      + ", pool.login-timeout "
                      + loginTimeout
                      + "; the database is not answering, check the url and that it is running)"
                  : ""),
          e);
    }
    return db;
  }

  private static boolean timedOut(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      String m = c.getMessage();
      if (m != null && m.toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
        return true;
      }
    }
    return false;
  }

  private static Problem outOfRange(InstanceConfig section, String key, String message) {
    return new Problem(ID, section.path() + "." + key, section.sourceOf(key), message, null);
  }
}
