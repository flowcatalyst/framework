package io.flowcatalyst.framework.sql.maven;

import io.flowcatalyst.framework.sql.CheckedSql;
import io.flowcatalyst.framework.sql.Layout;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Starts a throwaway PostgreSQL (or uses the empty database at {@code FW_SQL_DATABASE_URL}),
 * applies the project's migration sets, describes every query and writes the typed sources.
 */
@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true)
public class GenerateMojo extends SqlMojoSupport {

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("Skipping generate");
      return;
    }
    Layout layout = layout();
    Map<String, String> generated = describeAll(layout);
    CheckedSql.write(layout, generated);
    getLog().info("Wrote " + generated.size() + " generated file(s) to " + layout.outLabel());
    addSourceRoot();
  }
}
