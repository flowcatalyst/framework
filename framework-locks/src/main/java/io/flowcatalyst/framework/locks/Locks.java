package io.flowcatalyst.framework.locks;

import io.flowcatalyst.framework.core.Instances;
import java.util.Set;

/** The registry of named lock instances. Provided by {@link LocksModule}. */
public final class Locks {

  private final Instances<LockStore> instances;

  Locks(Instances<LockStore> instances) {
    this.instances = instances;
  }

  /**
   * Returns the named instance.
   *
   * @param name the instance name
   * @return the store
   * @throws IllegalArgumentException naming the known instances when there is no such instance
   */
  public LockStore get(String name) {
    return instances.get(name);
  }

  /**
   * The default instance.
   *
   * @return the instance named by {@code locks.default}, or the only instance
   * @throws IllegalStateException when no instance is configured
   */
  public LockStore defaultInstance() {
    return instances.defaultInstance();
  }

  /**
   * The configured instance names.
   *
   * @return names in configuration order
   */
  public Set<String> names() {
    return instances.names();
  }
}
