package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.core.Provider;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * How an application accepts field workers (contract section 15). Off by default: pass these to
 * {@link AuthModule.Builder#fieldWorkers}. The platform signs the token, the device signs a proof
 * for each request, and the application checks both; it never sees a PIN.
 *
 * <pre>{@code
 * AuthModule.builder()
 *     .application(new ApplicationCode("yard"))
 *     .fieldWorkers(FieldOptions.builder()
 *         .permissions(FieldPermissions.byDeviceMode(Set.of("yard:gate:open"), Set.of()))
 *         .replayCache(lookup -> RedisFieldReplayCache.create(lookup.get(RedisConnections.class).defaultInstance()))
 *         .build())
 * }</pre>
 *
 * <p>Needs {@code auth.cookie-keys} (the nonces derive from them) and {@link
 * AuthModule.Builder#application}, whose code a token's audience must equal.
 */
public final class FieldOptions {

  private final FieldPermissionResolver permissions;
  private final @Nullable Provider<? extends FieldReplayCache> replayCache;
  private final Duration proofWindow;

  private FieldOptions(Builder b) {
    this.permissions = b.permissions;
    this.replayCache = b.replayCache;
    this.proofWindow = b.proofWindow;
  }

  /**
   * Options with every default: no permissions, the memory replay cache, a 24 hour proof window.
   *
   * @return the options
   */
  public static FieldOptions defaults() {
    return builder().build();
  }

  /**
   * Starts options.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  FieldPermissionResolver permissions() {
    return permissions;
  }

  @Nullable Provider<? extends FieldReplayCache> replayCache() {
    return replayCache;
  }

  Duration proofWindow() {
    return proofWindow;
  }

  /** Builds {@link FieldOptions}. */
  public static final class Builder {

    private FieldPermissionResolver permissions = FieldPermissionResolver.of(FieldPermissions.none());
    private @Nullable Provider<? extends FieldReplayCache> replayCache;
    private Duration proofWindow = Duration.ofHours(24);

    private Builder() {}

    /**
     * What a field worker may do. Default {@link FieldPermissions#none()}: a route that requires a
     * permission refuses field workers until this maps them.
     *
     * @param value the mapping
     * @return this builder
     */
    public Builder permissions(FieldPermissions value) {
      this.permissions = FieldPermissionResolver.of(value);
      return this;
    }

    /**
     * What a field worker may do, looked up per request where the application holds it (a
     * database, a cache). Replaces {@link #permissions(FieldPermissions)}; the last of the two
     * given wins. A thrown exception answers 503, never "no permissions".
     *
     * @param value the resolver
     * @return this builder
     */
    public Builder permissionResolver(FieldPermissionResolver value) {
      this.permissions = value;
      return this;
    }

    /**
     * Where accepted proof ids are remembered, built when the application starts. Default: the
     * memory cache ({@link FieldReplayCache#memory}), which start-up warns about because replicas do
     * not share it. An application with several replicas passes the Redis cache.
     *
     * @param provider builds the cache
     * @return this builder
     */
    public Builder replayCache(Provider<? extends FieldReplayCache> provider) {
      this.replayCache = provider;
      return this;
    }

    /**
     * How far a proof's {@code iat} may be from the server clock, either way. Field devices keep
     * bad time, so freshness comes from the nonce: default 24 hours (the platform's own rule).
     *
     * @param value positive
     * @return this builder
     */
    public Builder proofWindow(Duration value) {
      if (value.isNegative() || value.isZero()) {
        throw new IllegalArgumentException("proofWindow must be positive");
      }
      this.proofWindow = value;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public FieldOptions build() {
      return new FieldOptions(this);
    }
  }
}
