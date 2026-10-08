package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Declaration;
import io.flowcatalyst.framework.config.Setting;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Describes an application and starts it.
 *
 * <p>The methods that declare, provide, register drivers and contribute are valid only inside a
 * module's {@code register} hook; elsewhere they throw {@link IllegalStateException}.
 *
 * <pre>{@code
 * App app = App.builder()
 *     .module(new LocksModule())
 *     .module(new OrdersModule())
 *     .start();
 * }</pre>
 */
public final class AppBuilder {

  private static final Pattern MODULE_ID = Pattern.compile("[a-z][a-z0-9-]*");

  private final List<Module> modules = new ArrayList<>();
  private Path configDirectory = Path.of("config");
  private Map<String, String> environment = System.getenv();
  private InstantSource instantSource = InstantSource.system();
  private MonotonicClock monotonicClock = MonotonicClock.system();
  private boolean installShutdownHook;
  private boolean started;

  // Registration state.
  private @Nullable String registering;
  private final Drivers drivers = new Drivers();
  private final List<Problem> problems = new ArrayList<>();
  private final List<Declaration> declarations = new ArrayList<>();
  private final Map<Class<?>, ServiceGraph.Entry> providers = new LinkedHashMap<>();
  private final Map<Class<?>, List<Object>> contributions = new LinkedHashMap<>();

  AppBuilder() {}

  // ---- configuring the application ---------------------------------------------------------

  /**
   * Adds a module. Order matters: {@code register} and {@code boot} run in this order, and
   * shutdown in reverse.
   *
   * @param module the module
   * @return this builder
   */
  public AppBuilder module(Module module) {
    requireNotStarted();
    modules.add(module);
    return this;
  }

  /**
   * Sets the config directory; the default is {@code config/} under the working directory.
   *
   * @param directory the directory holding {@code application.yaml} and friends
   * @return this builder
   */
  public AppBuilder configDirectory(Path directory) {
    requireNotStarted();
    this.configDirectory = directory;
    return this;
  }

  /**
   * Sets the environment variables to read; the default is the process environment. For tests.
   *
   * @param env variable names to values
   * @return this builder
   */
  public AppBuilder environment(Map<String, String> env) {
    requireNotStarted();
    this.environment = Map.copyOf(env);
    return this;
  }

  /**
   * Sets the wall-clock source offered to modules; the default is the system clock.
   *
   * @param source the source
   * @return this builder
   */
  public AppBuilder instantSource(InstantSource source) {
    requireNotStarted();
    this.instantSource = source;
    return this;
  }

  /**
   * Sets the monotonic clock offered to modules; the default is {@code System.nanoTime}.
   *
   * @param clock the clock
   * @return this builder
   */
  public AppBuilder monotonicClock(MonotonicClock clock) {
    requireNotStarted();
    this.monotonicClock = clock;
    return this;
  }

  /**
   * Whether to close the application from a JVM shutdown hook. The default is off, so tests do
   * not leave hooks behind; services turn it on.
   *
   * @param install true to install the hook
   * @return this builder
   */
  public AppBuilder shutdownHook(boolean install) {
    requireNotStarted();
    this.installShutdownHook = install;
    return this;
  }

  // ---- the register phase ------------------------------------------------------------------

  /**
   * Declares a setting. A key declared twice fails start-up.
   *
   * @param <T> the value type
   * @param setting the setting
   * @return the same setting, for the module to keep and read after start-up
   */
  public <T> Setting<T> declare(Setting<T> setting) {
    String module = requireRegistering("declare a setting");
    declarations.add(new Declaration(module, setting));
    return setting;
  }

  /**
   * Provides a service. Exactly one module provides each type. The provider runs during start-up,
   * after configuration is validated.
   *
   * @param <T> the service type
   * @param type the type other code asks for
   * @param provider builds the service
   */
  public <T> void provide(Class<T> type, Provider<? extends T> provider) {
    String module = requireRegistering("provide a service");
    ServiceGraph.Entry existing = providers.get(type);
    if (isFrameworkType(type)) {
      problems.add(
          Problem.of(
              module, type.getName(), "this type is provided by the framework itself"));
    } else if (existing != null) {
      problems.add(
          Problem.of(
              module,
              type.getName(),
              "already provided by module '" + existing.module() + "'"));
    } else {
      providers.put(type, new ServiceGraph.Entry(module, type, provider));
    }
  }

  /**
   * Registers drivers for capabilities.
   *
   * @return the registrar, bound to the module being registered
   */
  public Drivers.Registrar drivers() {
    String module = requireRegistering("register drivers");
    return new Drivers.Registrar(drivers, module, problems);
  }

  /**
   * Contributes a value that another module collects at start-up with
   * {@link Lookup#contributions(Class)}, for example a migration set.
   *
   * @param <T> the contribution type
   * @param type the contribution type
   * @param item the value
   */
  public <T> void contribute(Class<T> type, T item) {
    requireRegistering("contribute");
    contributions.computeIfAbsent(type, t -> new ArrayList<>()).add(item);
  }

  /**
   * The id of the module being registered.
   *
   * @return the module id
   */
  public String moduleId() {
    return requireRegistering("ask for the module id");
  }

  // ---- starting ----------------------------------------------------------------------------

  /**
   * Starts the application: registers modules, loads and validates configuration, builds every
   * service, and boots modules, in that order.
   *
   * @return the running application
   * @throws StartupException listing every problem found; services already built are closed
   */
  public App start() {
    requireNotStarted();
    started = true;

    List<Module> all = new ArrayList<>();
    all.add(new CoreModule());
    all.addAll(modules);

    // 1. Register, in the order listed.
    List<Module> registered = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Module module : all) {
      String id = module.id();
      if (!MODULE_ID.matcher(id).matches()) {
        problems.add(Problem.of(id, null, "module id must match [a-z][a-z0-9-]*"));
        continue;
      }
      if (!ids.add(id)) {
        problems.add(Problem.of(id, null, "duplicate module id"));
        continue;
      }
      registered.add(module);
      registering = id;
      try {
        module.register(this);
      } catch (Exception e) {
        problems.add(new Problem(id, null, null, "register failed: " + describe(e), e));
      } finally {
        registering = null;
      }
    }

    // 2. Load and validate configuration.
    Config config = null;
    switch (Config.load(configDirectory, environment, declarations)) {
      case Config.Ok ok -> config = ok.config();
      case Config.Failed failed ->
          failed.problems().forEach(p -> problems.add(Problem.fromConfig(p, null)));
    }
    if (config == null || !problems.isEmpty()) {
      throw new StartupException(problems);
    }

    java.time.Duration drain = config.get(CoreModule.SHUTDOWN_DRAIN);
    Health health = new Health(config.get(CoreModule.HEALTH_TIMEOUT), monotonicClock);

    // 3. Build every provided service.
    Map<Class<?>, Object> seeded = new LinkedHashMap<>();
    seeded.put(Config.class, config);
    seeded.put(Drivers.class, drivers);
    seeded.put(InstantSource.class, instantSource);
    seeded.put(Ids.class, new Ids(instantSource));
    seeded.put(MonotonicClock.class, monotonicClock);
    ServiceGraph graph = new ServiceGraph(providers, seeded, contributions);
    graph.buildAll();
    if (!graph.problems().isEmpty()) {
      Shutdown.closeInReverse(graph.builtInOrder(), drain);
      throw new StartupException(graph.problems());
    }

    // 4. Boot, in the order listed.
    App app =
        new App(config, health, drain, graph.services(), graph.order(), registered);
    List<Problem> bootProblems = new ArrayList<>();
    for (Module module : registered) {
      try {
        module.boot(app);
        app.markBooted(module);
      } catch (StartupException e) {
        // A hook that collected problems itself (for example route checks): report each one.
        bootProblems.addAll(e.problems());
      } catch (Exception e) {
        bootProblems.add(
            new Problem(module.id(), null, null, "boot failed: " + describe(e), e));
      }
    }
    if (!bootProblems.isEmpty()) {
      app.close();
      throw new StartupException(bootProblems);
    }
    if (installShutdownHook) {
      app.installShutdownHook();
    }
    return app;
  }

  private static boolean isFrameworkType(Class<?> type) {
    return type == Config.class
        || type == Drivers.class
        || type == InstantSource.class
        || type == Ids.class
        || type == MonotonicClock.class;
  }

  private String requireRegistering(String what) {
    String module = registering;
    if (module == null) {
      throw new IllegalStateException(
          "cannot " + what + " outside a module's register hook");
    }
    return module;
  }

  private void requireNotStarted() {
    if (started) {
      throw new IllegalStateException("this builder has already been started");
    }
  }

  private static String describe(Throwable t) {
    String m = t.getMessage();
    String first = m == null ? "" : m.lines().findFirst().orElse("");
    return t.getClass().getSimpleName() + (first.isEmpty() ? "" : ": " + first);
  }
}
