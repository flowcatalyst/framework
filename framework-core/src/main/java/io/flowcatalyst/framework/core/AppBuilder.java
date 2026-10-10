package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Declaration;
import io.flowcatalyst.framework.config.Setting;
import java.io.PrintStream;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger LOG = LoggerFactory.getLogger(AppBuilder.class);
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
  private final Map<String, Command> commands = new LinkedHashMap<>();
  private final Map<String, String> commandModules = new LinkedHashMap<>();
  private final List<Module> registeredModules = new ArrayList<>();
  private final Map<Class<?>, Object> overrides = new LinkedHashMap<>();

  AppBuilder() {}

  // ---- configuring the application ---------------------------------------------------------

  /**
   * Adds a module. Order matters: {@code register}, {@code boot} and {@code started} run in this
   * order, and shutdown in reverse.
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

  /**
   * Replaces the service a module provides for {@code type} with {@code instance}, <b>in tests
   * only</b>. The module's provider for that type is not run; every lookup of the type, including
   * other providers', gets this instance. The application does not close it: the test owns it.
   *
   * <p>Allowed only when the environment is {@code test} ({@code APP_ENV=test} in the builder's
   * {@link #environment(Map) environment}, or in the process environment when none was given).
   * Otherwise {@link #start()} and {@link #launch} fail with a {@link StartupException} before any
   * module is registered, so an override can never reach a deployed application. Start-up also fails
   * when no module provides {@code type} (overriding an implementation class instead of the provided
   * interface would otherwise silently do nothing) and for the framework's own types; use
   * {@link #instantSource} and {@link #monotonicClock} for the clocks.
   *
   * <pre>{@code
   * App app = App.builder()
   *     .environment(Map.of("APP_ENV", "test"))
   *     .module(new MailModule())
   *     .override(Mailer.class, fakeMailer)
   *     .start();
   * }</pre>
   *
   * @param <T> the service type
   * @param type the type a module provides
   * @param instance what lookups of {@code type} return instead
   * @return this builder
   * @throws IllegalArgumentException when {@code type} is already overridden
   */
  public <T> AppBuilder override(Class<T> type, T instance) {
    requireNotStarted();
    if (!type.isInstance(instance)) {
      throw new IllegalArgumentException(
          "override for " + type.getName() + " is a " + instance.getClass().getName());
    }
    if (overrides.putIfAbsent(type, instance) != null) {
      throw new IllegalArgumentException(type.getName() + " is already overridden");
    }
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
   * Claims a command name for {@link #launch}. Two modules claiming one name fails start-up.
   *
   * @param command the command
   */
  public void command(Command command) {
    String module = requireRegistering("claim a command");
    String name = command.name();
    if (!MODULE_ID.matcher(name).matches()) {
      problems.add(Problem.of(module, name, "command name must match [a-z][a-z0-9-]*"));
    } else if (commandModules.containsKey(name)) {
      problems.add(
          Problem.of(
              module, name, "command already claimed by module '" + commandModules.get(name) + "'"));
    } else {
      commands.put(name, command);
      commandModules.put(name, module);
    }
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
    return bootAndStart(assemble());
  }

  /**
   * Runs a module's {@link Command} when the first program argument names one, otherwise starts
   * the application exactly as {@link #start()} does. Unknown arguments are not an error: they fall
   * through to a normal start.
   *
   * <p>A command runs after registration, configuration and building services (steps 1 to 3 of
   * the lifecycle) and before any {@code boot}; the application is closed afterwards.
   *
   * <pre>{@code
   * switch (App.builder().module(...).launch(args)) {
   *   case Launch.Ran ran -> System.exit(ran.exitCode());
   *   case Launch.Started started -> started.app();
   * }
   * }</pre>
   *
   * @param args the program arguments
   * @return what happened
   * @throws StartupException listing every problem found while assembling or starting
   */
  public Launch launch(String... args) {
    App app = assemble();
    @Nullable Command command = args.length == 0 ? null : commands.get(args[0]);
    if (command == null) {
      return new Launch.Started(bootAndStart(app));
    }
    PrintStream out = System.out;
    try {
      int code = command.run(app, List.of(args).subList(1, args.length), out);
      out.flush();
      return new Launch.Ran(code);
    } catch (Exception e) {
      LOG.error("command '{}' failed: {}", command.name(), describe(e), e);
      return new Launch.Ran(1);
    } finally {
      app.close();
    }
  }

  /** Steps 1 to 3: register, load configuration, build services. Nothing is booted. */
  private App assemble() {
    requireNotStarted();
    started = true;
    requireTestModeForOverrides();

    List<Module> all = new ArrayList<>();
    all.add(new CoreModule());
    all.addAll(modules);

    // 1. Register, in the order listed.
    List<Module> registered = registeredModules;
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
    Set<String> forcedCritical = new HashSet<>(config.get(CoreModule.HEALTH_CRITICAL));
    Set<String> forcedInformational = new HashSet<>(config.get(CoreModule.HEALTH_INFORMATIONAL));
    for (String name : forcedCritical) {
      if (forcedInformational.contains(name)) {
        problems.add(
            Problem.of(
                CoreModule.ID,
                CoreModule.HEALTH_INFORMATIONAL.key(),
                "health check '" + name + "' is also listed in " + CoreModule.HEALTH_CRITICAL.key()));
      }
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }
    Health health =
        new Health(
            config.get(CoreModule.HEALTH_TIMEOUT),
            monotonicClock,
            forcedCritical,
            forcedInformational);

    // 3. Build every provided service.
    Map<Class<?>, Object> seeded = new LinkedHashMap<>();
    seeded.put(Config.class, config);
    seeded.put(Drivers.class, drivers);
    seeded.put(InstantSource.class, instantSource);
    seeded.put(Ids.class, new Ids(instantSource));
    seeded.put(MonotonicClock.class, monotonicClock);
    seeded.put(Health.class, health);
    applyOverrides(seeded);
    ServiceGraph graph = new ServiceGraph(providers, seeded, contributions);
    graph.buildAll();
    if (!graph.problems().isEmpty()) {
      Shutdown.closeInReverse(graph.builtInOrder(), drain);
      throw new StartupException(graph.problems());
    }
    return new App(config, health, drain, graph.services(), graph.order(), registered);
  }

  /** Steps 4 and 5: boot, then started. */
  private App bootAndStart(App app) {
    List<Module> registered = registeredModules;
    // 4. Boot, in the order listed.
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

    // 5. Started, once every module has booted, in the order listed.
    List<Problem> startedProblems = new ArrayList<>();
    for (Module module : registered) {
      try {
        module.started(app);
      } catch (StartupException e) {
        startedProblems.addAll(e.problems());
      } catch (Exception e) {
        startedProblems.add(
            new Problem(module.id(), null, null, "started failed: " + describe(e), e));
      }
    }
    if (!startedProblems.isEmpty()) {
      app.close();
      throw new StartupException(startedProblems);
    }
    // Checks are added in boot (some only conditionally, such as the outbox check), so a name in
    // the criticality overrides that matches nothing is a warning, not a failure.
    List<String> unmatched = app.health().unmatchedOverrides();
    if (!unmatched.isEmpty()) {
      LOG.warn(
          "app.health.critical / app.health.informational name checks that do not exist: {}",
          unmatched);
    }
    // Loops registered during boot and started begin only now, with the application whole.
    app.find(Background.class).ifPresent(Background::startAll);
    if (installShutdownHook) {
      app.installShutdownHook();
    }
    return app;
  }

  /** Fails, before anything is registered, when overrides are given outside test mode. */
  private void requireTestModeForOverrides() {
    if (overrides.isEmpty()) {
      return;
    }
    String env = environment.get("APP_ENV");
    if (env == null || env.isBlank()) {
      env = "local";
    }
    if (!env.equals("test")) {
      List<Problem> refused = new ArrayList<>();
      for (Class<?> type : overrides.keySet()) {
        refused.add(
            Problem.of(
                CoreModule.ID,
                type.getName(),
                "AppBuilder.override is allowed only when APP_ENV is 'test' (it is '"
                    + env
                    + "')"));
      }
      throw new StartupException(refused);
    }
  }

  /**
   * Puts each override among the seeded services and removes the module's provider, so it is never
   * run. Seeded services are not closed at shutdown, which is right: the test owns the instance.
   */
  private void applyOverrides(Map<Class<?>, Object> seeded) {
    List<Problem> found = new ArrayList<>();
    for (Map.Entry<Class<?>, Object> e : overrides.entrySet()) {
      Class<?> type = e.getKey();
      if (isFrameworkType(type)) {
        found.add(
            Problem.of(
                CoreModule.ID,
                type.getName(),
                "a framework type cannot be overridden; use the builder's own method (instantSource,"
                    + " monotonicClock, environment)"));
        continue;
      }
      ServiceGraph.Entry entry = providers.remove(type);
      if (entry == null) {
        found.add(
            Problem.of(
                CoreModule.ID,
                type.getName(),
                "overridden, but no module provides this type (override the type modules look up)"));
        continue;
      }
      seeded.put(type, e.getValue());
      LOG.warn(
          "test mode: service {} from module '{}' is overridden",
          type.getName(),
          entry.module());
    }
    if (!found.isEmpty()) {
      throw new StartupException(found);
    }
  }

  private static boolean isFrameworkType(Class<?> type) {
    return type == Config.class
        || type == Drivers.class
        || type == InstantSource.class
        || type == Ids.class
        || type == MonotonicClock.class
        || type == Health.class;
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
