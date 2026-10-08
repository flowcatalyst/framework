package io.flowcatalyst.framework.database;

import io.flowcatalyst.framework.core.Instances;
import java.util.Set;

/** The registry of named database instances. Provided by {@link DatabaseModule}. */
public final class Databases implements AutoCloseable {

  private final Instances<Database> instances;

  Databases(Instances<Database> instances) {
    this.instances = instances;
  }

  /**
   * Returns the named instance.
   *
   * @param name the instance name
   * @return the instance
   * @throws IllegalArgumentException naming the known instances when there is no such instance
   */
  public Database get(String name) {
    return instances.get(name);
  }

  /**
   * The default instance, the one migrations run on.
   *
   * @return the instance named by {@code database.default}, or the only instance
   * @throws IllegalStateException when no instance is configured
   */
  public Database defaultInstance() {
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

  /** Closes every pool. */
  @Override
  public void close() {
    instances.close();
  }
}
