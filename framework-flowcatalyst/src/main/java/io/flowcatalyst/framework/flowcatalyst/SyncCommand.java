package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Command;
import java.io.PrintStream;
import java.util.List;

/**
 * The {@code sync [--dry-run]} command: {@code java -jar app.jar sync} pushes the application's
 * definitions to the platform and exits; with {@code --dry-run} it only reports what would be sent.
 *
 * <p>Exit codes: 0 synced, 1 at least one category failed, 2 bad arguments or the module is
 * disabled.
 */
final class SyncCommand implements Command {

  static final String NAME = "sync";
  private static final String USAGE = "usage: sync [--dry-run]";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public int run(App app, List<String> args, PrintStream out) {
    DefinitionSync.Mode mode = DefinitionSync.Mode.APPLY;
    for (String arg : args) {
      if ("--dry-run".equals(arg)) {
        mode = DefinitionSync.Mode.DRY_RUN;
      } else {
        out.println("unknown argument: " + arg);
        out.println(USAGE);
        return 2;
      }
    }
    if (!app.get(PlatformClient.class).isEnabled()) {
      out.println("flowcatalyst is disabled (flowcatalyst.enabled=false); nothing to sync");
      return 2;
    }
    try {
      SyncReport report = app.get(DefinitionSync.class).run(mode);
      report.describe().forEach(out::println);
      out.println(mode == DefinitionSync.Mode.DRY_RUN ? "dry run: nothing was sent" : "sync complete");
      return 0;
    } catch (SyncException e) {
      e.report().describe().forEach(out::println);
      out.println("sync failed");
      return 1;
    }
  }
}
