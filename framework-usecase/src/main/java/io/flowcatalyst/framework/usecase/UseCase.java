package io.flowcatalyst.framework.usecase;

import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A use case, defined once: a name, optional validation, required authorization, execute, and a
 * conflict policy. Run it with {@link UseCases#run}.
 *
 * <pre>{@code
 * UseCase<CancelOrder, OrderCancelled> cancel =
 *     UseCase.named("cancel-order", CancelOrder.class, OrderCancelled.class)
 *         .validate((ctx, cmd) -> ...)
 *         .authorize((ctx, cmd) -> ...)          // or .authorize(Authorize.PUBLIC)
 *         .conflict(ConflictPolicy.retry(3, Duration.ofMillis(20)))
 *         .execute((ctx, cmd) -> ...);           // returns the finished use case
 * }</pre>
 *
 * @param <C> the command type
 * @param <E> the type of the result event
 */
public final class UseCase<C, E extends DomainEvent> {

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

  private final String name;
  private final @Nullable Check<? super C> validate;
  private final Check<? super C> authorize;
  private final Execute<C, E> execute;
  private final ConflictPolicy conflict;

  private UseCase(
      String name,
      @Nullable Check<? super C> validate,
      Check<? super C> authorize,
      Execute<C, E> execute,
      ConflictPolicy conflict) {
    this.name = name;
    this.validate = validate;
    this.authorize = authorize;
    this.execute = execute;
    this.conflict = conflict;
  }

  /**
   * Starts defining a use case.
   *
   * @param <C> the command type
   * @param <E> the result event type
   * @param name for logs and the audit row; matches {@code [a-z][a-z0-9-]*}
   * @param commandType the command class, to fix the type
   * @param eventType the result event class, to fix the type
   * @return the builder
   * @throws IllegalArgumentException when the name is malformed
   */
  public static <C, E extends DomainEvent> Builder<C, E> named(
      String name, Class<C> commandType, Class<E> eventType) {
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("use case name must match [a-z][a-z0-9-]*: " + name);
    }
    return new Builder<>(name);
  }

  /**
   * The use case name.
   *
   * @return the name
   */
  public String name() {
    return name;
  }

  Optional<Check<? super C>> validate() {
    return Optional.ofNullable(validate);
  }

  Check<? super C> authorize() {
    return authorize;
  }

  Execute<C, E> execute() {
    return execute;
  }

  ConflictPolicy conflict() {
    return conflict;
  }

  /**
   * Collects the parts of a use case. Authorization is required and comes before execute.
   *
   * @param <C> the command type
   * @param <E> the result event type
   */
  public static final class Builder<C, E extends DomainEvent> {
    private final String name;
    private @Nullable Check<? super C> validate;
    private @Nullable Check<? super C> authorize;
    private ConflictPolicy conflict = ConflictPolicy.fail();

    private Builder(String name) {
      this.name = name;
    }

    /**
     * Checks the command's shape, without loading data.
     *
     * @param check the check
     * @return this builder
     */
    public Builder<C, E> validate(Check<? super C> check) {
      this.validate = check;
      return this;
    }

    /**
     * The resource-level access check. {@link Authorize#PUBLIC} declares the operation open.
     *
     * @param check the check
     * @return this builder
     */
    public Builder<C, E> authorize(Check<? super C> check) {
      this.authorize = check;
      return this;
    }

    /**
     * What to do on a conflict; the default is {@link ConflictPolicy#fail()}.
     *
     * @param policy the policy
     * @return this builder
     */
    public Builder<C, E> conflict(ConflictPolicy policy) {
      this.conflict = policy;
      return this;
    }

    /**
     * Adds a load phase: the runner finds the record once per attempt, after validate and
     * authorize, then runs the record-level check and execute with it. A missing record ends the
     * run with {@code whenMissing} and nothing after it runs.
     *
     * @param <L> what is loaded
     * @param load finds the record
     * @param whenMissing the error when there is no such record (usually {@code NOT_FOUND})
     * @return the builder for the phases that see the record
     */
    public <L> LoadedBuilder<C, L, E> load(Load<? super C, L> load, UseCaseError whenMissing) {
      return new LoadedBuilder<>(this, load, whenMissing);
    }

    /**
     * Sets the execute phase and finishes the definition.
     *
     * @param execute the phase
     * @return the use case
     * @throws IllegalStateException when no authorization was declared
     */
    public UseCase<C, E> execute(Execute<C, E> execute) {
      Check<? super C> auth = authorize;
      if (auth == null) {
        throw new IllegalStateException(
            "use case '"
                + name
                + "' has no authorize step; declare one, or Authorize.PUBLIC if it is open");
      }
      return new UseCase<>(name, validate, auth, execute, conflict);
    }
  }

  /**
   * The phases after a load: an optional record-level check, then execute with the record.
   *
   * @param <C> the command type
   * @param <L> what is loaded
   * @param <E> the result event type
   */
  public static final class LoadedBuilder<C, L, E extends DomainEvent> {
    private final Builder<C, E> parent;
    private final Load<? super C, L> load;
    private final UseCaseError whenMissing;
    private @Nullable LoadedCheck<? super C, ? super L> check;

    private LoadedBuilder(Builder<C, E> parent, Load<? super C, L> load, UseCaseError whenMissing) {
      this.parent = parent;
      this.load = load;
      this.whenMissing = whenMissing;
    }

    /**
     * The record-level access check, run after the load and before execute. Optional.
     *
     * @param check the check
     * @return this builder
     */
    public LoadedBuilder<C, L, E> authorizeLoaded(LoadedCheck<? super C, ? super L> check) {
      this.check = check;
      return this;
    }

    /**
     * Sets the execute phase and finishes the definition.
     *
     * @param execute the phase, given the loaded record
     * @return the use case
     * @throws IllegalStateException when no authorization was declared
     */
    public UseCase<C, E> execute(LoadedExecute<C, L, E> execute) {
      Load<? super C, L> loader = load;
      UseCaseError missing = whenMissing;
      LoadedCheck<? super C, ? super L> recordCheck = check;
      return parent.execute(
          (context, command) -> {
            Optional<L> found;
            try {
              found = loader.load(context, command);
            } catch (Exception e) {
              throw new PhaseException("LOAD", e);
            }
            if (found.isEmpty()) {
              return Decision.reject(missing);
            }
            L loaded = found.get();
            if (recordCheck != null) {
              Optional<UseCaseError> denied;
              try {
                denied = recordCheck.check(context, command, loaded);
              } catch (Exception e) {
                throw new PhaseException("AUTHORIZE", e);
              }
              if (denied.isPresent()) {
                return Decision.reject(denied.get());
              }
            }
            return execute.execute(context, command, loaded);
          });
    }
  }
}
