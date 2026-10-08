package io.flowcatalyst.framework.sql;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles generated sources in the test, as javac would when the project builds. */
final class GeneratedCode {
  private GeneratedCode() {}

  /** Compiles the sources (name to text, for example {@code a.b.C} to its source); returns a loader. */
  static ClassLoader compile(Path outDir, Map<String, String> sources) throws IOException {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    StringWriter log = new StringWriter();
    List<JavaFileObject> units =
        sources.entrySet().stream()
            .map(e -> (JavaFileObject) new Source(e.getKey(), e.getValue()))
            .toList();
    Files.createDirectories(outDir);
    try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, null)) {
      boolean ok =
          javac
              .getTask(
                  log,
                  fm,
                  diagnostics,
                  List.of("-d", outDir.toString(), "-Xlint:all", "-proc:none", "--release", "25"),
                  null,
                  units)
              .call();
      StringBuilder messages = new StringBuilder();
      for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
        messages.append(d.getKind()).append(' ').append(d.getLineNumber()).append(": ").append(d.getMessage(null)).append('\n');
      }
      if (!ok || !diagnostics.getDiagnostics().isEmpty()) {
        throw new AssertionError("generated code does not compile cleanly:\n" + messages + "\n" + sources);
      }
    }
    return new URLClassLoader(new URL[] {outDir.toUri().toURL()}, GeneratedCode.class.getClassLoader());
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String text;

    Source(String className, String text) {
      super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }
}
