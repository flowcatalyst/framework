package io.flowcatalyst.framework.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.InstanceConfig;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Instances;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Provides {@link Databases}: the instances configured under {@code database}, then applies the
 * migration sets contributed by modules to the default instance.
 *
 * <p>Configuration holds each instance's connection only; the pool and whether migrations run are
 * code (config-and-code contract, section 3.2):
 *
 * <pre>{@code
 * database:
 *   default: main
 *   instances:
 *     main:
 *       url: jdbc:postgresql://localhost:5432/app
 *       username: app
 *       password: ""          # a secret: DATABASE_INSTANCES_MAIN_PASSWORD
 * }</pre>
 *
 * <pre>{@code
 * new DatabaseModule()                                     // the defaults
 * DatabaseModule.builder()
 *     .pool(DatabasePool.defaults().withMaxSize(20))       // every instance
 *     .pool("reporting", DatabasePool.defaults().withMaxSize(4))
 *     .migrate(false)                                      // migrations run by a separate job
 *     .build()
 * }</pre>
 *
 * <p>PostgreSQL is the only database. Each instance's health check is {@code database:<name>}.
 */
public final class DatabaseModule implements Module {

  /** The module id. */
  public static final String ID = "database";

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]*");

  private final Function<Config, DatabasePool> pool;
  private final Map<String, Function<Config, DatabasePool>> pools;
  private final Predicate<Config> migrate;

  /** The defaults: {@link DatabasePool#defaults()} for every instance, migrations on. */
  public DatabaseModule() {
    this(builder());
  }

  private DatabaseModule(Builder b) {
    this.pool = b.pool;
    this.pools = new LinkedHashMap<>(b.pools);
    this.migrate = b.migrate;
  }

  /**
   * Starts a module definition with the defaults.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds a {@link DatabaseModule}. */
  public static final class Builder {
    private Function<Config, DatabasePool> pool = c -> DatabasePool.defaults();
    private final Map<String, Function<Config, DatabasePool>> pools = new LinkedHashMap<>();
    private Predicate<Config> migrate = c -> true;

    private Builder() {}

    /**
     * The pool of every instance without one of its own (default {@link DatabasePool#defaults()}).
     *
     * @param value the pool
     * @return this builder
     */
    public Builder pool(DatabasePool value) {
      this.pool = c -> value;
      return this;
    }

    /**
     * As {@link #pool(DatabasePool)}, computed from configuration once it is loaded (an
     * application's own setting).
     *
     * @param value computes the pool
     * @return this builder
     */
    public Builder pool(Function<Config, DatabasePool> value) {
      this.pool = value;
      return this;
    }

    /**
     * The pool of one instance. Naming an instance that is not configured fails start-up.
     *
     * @param instance the instance name under {@code database.instances}
     * @param value the pool
     * @return this builder
     * @throws IllegalArgumentException for an invalid or repeated name
     */
    public Builder pool(String instance, DatabasePool value) {
      return pool(instance, c -> value);
    }

    /**
     * As {@link #pool(String, DatabasePool)}, computed from configuration once it is loaded.
     *
     * @param instance the instance name
     * @param value computes the pool
     * @return this builder
     * @throws IllegalArgumentException for an invalid or repeated name
     */
    public Builder pool(String instance, Function<Config, DatabasePool> value) {
      if (!NAME.matcher(instance).matches()) {
        throw new IllegalArgumentException("invalid database instance name '" + instance + "'");
      }
      if (pools.putIfAbsent(instance, value) != null) {
        throw new IllegalArgumentException(
            "a pool for database instance '" + instance + "' is already given");
      }
      return this;
    }

    /**
     * Whether migrations run on the default instance at start-up (default true). False when a
     * separate job applies them.
     *
     * @param value true to run them
     * @return this builder
     */
    public Builder migrate(boolean value) {
      this.migrate = c -> value;
      return this;
    }

    /**
     * As {@link #migrate(boolean)}, decided from configuration once it is loaded.
     *
     * @param value decides
     * @return this builder
     */
    public Builder migrate(Predicate<Config> value) {
      this.migrate = value;
      return this;
    }

    /**
     * Builds the module.
     *
     * @return the module
     */
    public DatabaseModule build() {
      return new DatabaseModule(this);
    }
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.provide(
        Databases.class,
        lookup -> {
          Config config = lookup.get(Config.class);
          boolean migrates = migrate.test(config);
          DatabasePool common = pool.apply(config);
          Map<String, DatabasePool> own = new LinkedHashMap<>();
          for (Map.Entry<String, Function<Config, DatabasePool>> e : pools.entrySet()) {
            own.put(e.getKey(), e.getValue().apply(config));
          }
          requireConfigured(own.keySet(), config);
          Instances<Database> instances =
              Instances.build(
                  Database.class,
                  "database",
                  lookup,
                  (name, section, l, built) ->
                      createInstance(name, section, own.getOrDefault(name, common), migrates));
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

  /** A pool given in code for an instance that is not configured is a typo: fail start-up. */
  private static void requireConfigured(Iterable<String> named, Config config) {
    var configured = config.section("database").section("instances").keys();
    List<Problem> problems = new ArrayList<>();
    for (String name : named) {
      if (!configured.contains(name)) {
        problems.add(
            Problem.of(
                ID,
                "database.instances." + name,
                "DatabaseModule.builder().pool(\""
                    + name
                    + "\", ...) names an instance that is not configured; configured: "
                    + (configured.isEmpty() ? "(none)" : String.join(", ", configured))));
      }
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
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
      String name, InstanceConfig config, DatabasePool pool, boolean migrate) {
    HikariConfig hikari = new HikariConfig();
    hikari.setPoolName("fw-db-" + name);
    hikari.setJdbcUrl(config.string("url"));
    hikari.setUsername(config.string("username", ""));
    Secret password = config.findSecret("password").orElse(Secret.of(""));
    hikari.setPassword(password.reveal());
    hikari.setMaximumPoolSize(pool.maxSize());
    hikari.setMinimumIdle(pool.minIdle());
    hikari.setConnectionTimeout(pool.connectTimeout().toMillis());
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
