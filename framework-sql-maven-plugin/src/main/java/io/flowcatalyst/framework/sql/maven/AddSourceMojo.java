package io.flowcatalyst.framework.sql.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Adds the generated-source directory to the compile source roots, so the committed code is
 * compiled by builds that do not run {@code generate}. No database is needed.
 */
@Mojo(name = "add-source", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class AddSourceMojo extends SqlMojoSupport {

  @Override
  public void execute() {
    if (!skip) {
      addSourceRoot();
    }
  }
}
