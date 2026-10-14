package io.flowcatalyst.framework.examples.locksdemo;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.HealthReport;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.DatabasePool;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.locks.LocksModule;
import io.flowcatalyst.framework.locks.WithLockResult;
import io.flowcatalyst.framework.locks.database.LocksDatabaseModule;
import io.flowcatalyst.framework.locks.redis.LocksRedisModule;
import io.flowcatalyst.framework.redis.RedisModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts an application with the database, Redis and memory lock drivers configured, then takes a
 * lock on every instance. Run it with a PostgreSQL and a Redis reachable at the addresses in
 * {@code config/application.yaml}.
 */
public final class LocksDemo {

  private LocksDemo() {}

  /**
   * The application: every module the three lock drivers need, listed in code.
   *
   * @return a builder with the modules added
   */
  public static AppBuilder builder() {
    return App.builder()
        // behaviour is code: a short connect timeout, so a missing database fails start-up fast
        .module(
            DatabaseModule.builder()
                .pool(DatabasePool.defaults().withConnectTimeout(Duration.ofSeconds(2)))
                .build())
        .module(new RedisModule())
        .module(new LocksModule())
        .module(new LocksDatabaseModule())
        .module(new LocksRedisModule());
  }

  /**
   * Takes a lock on every configured instance and reports what happened.
   *
   * @param app a running application
   * @return one line per instance, then the health summary
   * @throws InterruptedException when interrupted while waiting for a lock
   */
  public static List<String> run(App app) throws InterruptedException {
    List<String> report = new ArrayList<>();
    Locks locks = app.get(Locks.class);
    for (String name : locks.names()) {
      LockStore store = locks.get(name);
      WithLockResult<String> result =
          store.withLock(
              LockKey.service("locks-demo"),
              Duration.ofSeconds(10),
              Duration.ofSeconds(5),
              lease -> "token " + lease.fencingToken());
      String outcome =
          switch (result) {
            case WithLockResult.Completed<String> c -> "completed with " + c.value();
            case WithLockResult.NotAcquired<String> n -> "not acquired";
            case WithLockResult.Lost<String> l -> "lost while running";
          };
      report.add(name + ": " + outcome);
    }
    HealthReport health = app.health().check();
    report.add("health: " + (health.isUp() ? "up" : "DOWN") + " " + health.results().keySet());
    return report;
  }

  /**
   * Starts, runs once and shuts down.
   *
   * @param args ignored
   * @throws InterruptedException when interrupted
   */
  public static void main(String[] args) throws InterruptedException {
    try (App app = builder().shutdownHook(true).start()) {
      run(app).forEach(System.out::println);
    }
  }
}
