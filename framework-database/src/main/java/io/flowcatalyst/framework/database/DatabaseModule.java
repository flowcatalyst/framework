package io.flowcatalyst.framework.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.framework.config.InstanceConfig;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Instances;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.StartupException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
 * <p>Instances name no {@code driver}; {@value #DRIVER} is the only one. Each instance's health
 * check is {@code database:<name>}.
 */
public final class DatabaseModule implements Module {

  /** The module id. */
  public static final String ID = "database";

  /** The only driver: PostgreSQL through HikariCP. */
  public static final String DRIVER = "postgresql";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.drivers().register(Database.class, DRIVER, DatabaseModule::createInstance);
    app.provide(
        Databases.class,
        lookup -> {
          Instances<Database> instances =
              Instances.build(Database.class, "database", lookup, DRIVER);
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

  private static Database createInstance(
      String name,
      InstanceConfig config,
      io.flowcatalyst.framework.core.Lookup lookup,
      Map<String, Database> built) {
    HikariConfig hikari = new HikariConfig();
    hikari.setPoolName("fw-db-" + name);
    hikari.setJdbcUrl(config.string("url"));
    hikari.setUsername(config.string("username", ""));
    Secret password = config.findSecret("password").orElse(Secret.of(""));
    hikari.setPassword(password.reveal());
    InstanceConfig pool = config.section("pool");
    hikari.setMaximumPoolSize(pool.integer("max", 10));
    hikari.setMinimumIdle(pool.integer("min-idle", 1));
    Duration connectTimeout = pool.duration("connect-timeout", Duration.ofSeconds(5));
    hikari.setConnectionTimeout(Math.max(250L, connectTimeout.toMillis()));
    boolean migrate = config.bool("migrate", true);
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
}
