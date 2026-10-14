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
 *       pool: { max: 10, min-idle: 1, connect-timeout: 5s }
 *       migrate: true
 * }</pre>
 *
 * <p>An instance is a connection, so all of it is configuration (config-and-code contract, section
 * 3.2). The pool keys are checked at start-up: {@code pool.max} 1 to 1000, {@code pool.min-idle} 0
 * to {@code pool.max}, {@code pool.connect-timeout} 250 ms to 5 minutes. {@code migrate} counts on
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
    // Fail in the check below, with the instance's name, rather than inside the pool constructor.
    hikari.setInitializationFailTimeout(-1);

    HikariDataSource pooled = new HikariDataSource(hikari);
    Database db = new Database(name, pooled, migrate);
    try {
      db.ping();
    } catch (SQLException | RuntimeException e) {
      pooled.close();
      throw new IllegalStateException(
          "cannot connect to database instance '" + name + "': " + e.getMessage(), e);
    }
    return db;
  }

  private static Problem outOfRange(InstanceConfig section, String key, String message) {
    return new Problem(ID, section.path() + "." + key, section.sourceOf(key), message, null);
  }
}
