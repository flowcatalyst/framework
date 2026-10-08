package io.flowcatalyst.framework.sql.maven;

import io.flowcatalyst.framework.sql.CheckedSql;
import io.flowcatalyst.framework.sql.Layout;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Describes every query as {@code generate} does, writes nothing, and fails if a committed
 * generated file differs from the output, is missing, or has no query file.
 */
@Mojo(
    name = "check",
    defaultPhase = LifecyclePhase.VERIFY,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true)
public class CheckMojo extends SqlMojoSupport {

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("Skipping check");
      return;
    }
    Layout layout = layout();
    Map<String, String> generated = describeAll(layout);
    List<String> problems = CheckedSql.check(layout, generated);
    if (!problems.isEmpty()) {
      throw new MojoFailureException(
          "Generated SQL code is out of date:\n  "
              + String.join("\n  ", problems)
              + "\nRun: ./mvnw io.flowcatalyst.framework:framework-sql-maven-plugin:generate"
              + " and commit the result.");
    }
    getLog().info("Generated SQL code is current (" + generated.size() + " file(s))");
  }
}
