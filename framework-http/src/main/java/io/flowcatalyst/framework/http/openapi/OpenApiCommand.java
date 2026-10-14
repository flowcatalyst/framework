package io.flowcatalyst.framework.http.openapi;

import io.flowcatalyst.framework.core.DeclarationCommand;
import io.flowcatalyst.framework.core.Declarations;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code openapi [--out <file>]}: writes the document after {@code register} only (contract section
 * 9). Every problem is printed, one per line, and the exit code is 1.
 */
final class OpenApiCommand implements DeclarationCommand {

  @Override
  public String name() {
    return "openapi";
  }

  @Override
  public int run(Declarations declarations, List<String> args, PrintStream out) throws Exception {
    Path file = null;
    if (args.size() == 2 && args.getFirst().equals("--out")) {
      file = Path.of(args.get(1));
    } else if (!args.isEmpty()) {
      out.println("usage: openapi [--out <file>]");
      return 2;
    }
    switch (OpenApi.document(declarations)) {
      case OpenApiDocument.Refused refused -> {
        out.println("openapi: the document cannot be written (" + refused.problems().size() + " problems)");
        for (String p : refused.problems()) {
          out.println("  - " + p);
        }
        return 1;
      }
      case OpenApiDocument.Written written -> {
        if (file == null) {
          out.print(written.json());
        } else {
          Path parent = file.toAbsolutePath().getParent();
          if (parent != null) {
            Files.createDirectories(parent);
          }
          Files.writeString(file, written.json(), StandardCharsets.UTF_8);
          out.println("openapi: wrote " + file);
        }
        return 0;
      }
    }
  }
}
