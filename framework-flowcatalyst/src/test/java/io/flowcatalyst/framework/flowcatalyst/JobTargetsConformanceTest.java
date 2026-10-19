package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.flowcatalyst.DefinitionSync.Mode;
import io.flowcatalyst.framework.locks.LockStores;
import io.flowcatalyst.framework.locks.LocksModule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks {@code conformance/flowcatalyst/job-targets.json} (flowcatalyst contract section
 * 4c): the {@code targetUrl} the definition sync sends for each scheduled job, given the settings,
 * which job handlers are registered and what the jobs declare. The cases run the real sync against a
 * fake platform. Java writes it; Go compares.
 */
class JobTargetsConformanceTest {

  static final String FILE = "flowcatalyst/job-targets.json";

  @TempDir Path dir;

  /** A job as declared: code and target (null or "" for none). */
  record Job(String code, String targetUrl) {}

  /** One case; expectTargets is per job in order (null = no targetUrl sent), or problem is a start-up refusal. */
  record Case(
      String name,
      Map<String, String> settings,
      List<String> handlers,
      List<Job> jobs,
      List<String> expectTargets,
      String problem) {}

  private static Map<String, String> s(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  private static final String BASE = "flowcatalyst.subscription-target-base-url";
  private static final String PATH = "flowcatalyst.receivers.path";
  private static final String ENABLED = "flowcatalyst.receivers.enabled";
  private static final Job NONE = new Job("none", null);
  private static final Job BLANK = new Job("blank", "  ");
  private static final Job REL = new Job("rel", "/custom/run");
  private static final Job ABS = new Job("abs", "https://elsewhere.example/jobs");

  private static List<Case> cases() {
    String base = "http://svc.example:8091";
    return List.of(
        new Case("no target, base, handler: the service's own receiver", s(BASE, base + "/"), List.of("none"), List.of(NONE), List.of(base + "/fc/scheduled-jobs"), null),
        new Case("blank target is no target", s(BASE, base), List.of("blank"), List.of(BLANK), List.of(base + "/fc/scheduled-jobs"), null),
        new Case("a path target resolves against the base, an absolute one is sent as declared", s(BASE, base + "/"), List.of("none"), List.of(NONE, REL, ABS), List.of(base + "/fc/scheduled-jobs", base + "/custom/run", "https://elsewhere.example/jobs"), null),
        new Case("receivers.path is honoured", s(BASE, base, PATH, "/hooks"), List.of("none"), List.of(NONE), List.of(base + "/hooks/scheduled-jobs"), null),
        new Case("a base with a path keeps it; trailing slashes are dropped", s(BASE, base + "/svc//"), List.of("none"), List.of(NONE, REL), List.of(base + "/svc/fc/scheduled-jobs", base + "/svc/custom/run"), null),
        new Case("no handler registered: no guess", s(BASE, base), List.of(), List.of(NONE), java.util.Arrays.asList((String) null), null),
        new Case("a handler for another job still counts (any handler)", s(BASE, base), List.of("other"), List.of(NONE), List.of(base + "/fc/scheduled-jobs"), null),
        new Case("receivers disabled: no guess", s(BASE, base, ENABLED, "false"), List.of("none"), List.of(NONE), java.util.Arrays.asList((String) null), null),
        new Case("no base: sent as declared, nothing guessed", Map.of(), List.of("none"), List.of(NONE, ABS), java.util.Arrays.asList(null, "https://elsewhere.example/jobs"), null),
        new Case("a path target without a base is refused at start-up", Map.of(), List.of("none"), List.of(REL), null, "targetUrl '/custom/run' is a path; set flowcatalyst.subscription-target-base-url"),
        new Case("a non-http target is refused at start-up", s(BASE, base), List.of("none"), List.of(new Job("ftp", "ftp://x/y")), null, "targetUrl must be an absolute http(s) URL or a path starting with '/'"));
  }

  @Test
  void theVectorMatchesTheTargetsTheJavaSyncSends() {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "The targetUrl the definition sync sends for each scheduled job (flowcatalyst contract section 4c). The"
            + " platform has no default target: a job without one fails every firing. Setup per case: settings"
            + " (flowcatalyst.* keys; unset = default, receivers.path '/fc', receivers.enabled true), handlers"
            + " (job codes with a registered scheduled-job handler), jobs (code and declared targetUrl; null or"
            + " blank = none). With subscription-target-base-url set: a job that declares no target and the"
            + " service has any scheduled-job handler registered and receivers enabled is sent"
            + " {base without trailing slashes}{receivers.path}/scheduled-jobs; a path target ('/...') is sent"
            + " {base without trailing slashes}{path}; an absolute target is sent as declared (trimmed). With no base,"
            + " the declared target is sent as declared, or none. expect.targets is the targetUrl per job in order"
            + " (null = the field is absent or empty). A case with expect.problem is refused at start-up and the"
            + " message of a validation problem contains that text: a path target needs the base; any target that is"
            + " neither an absolute http(s) URL nor a path is refused.");
    doc.put(
        "howToRun",
        "For each case configure the settings, register a scheduled-job handler for each code in handlers, declare"
            + " the jobs, then run the sync (apply) against a fake platform and compare the targetUrl of each job in the"
            + " scheduled-jobs request with expect.targets (null = absent or empty); for expect.problem assert that"
            + " start-up/validation fails with a problem containing the text. Regenerate from Java with"
            + " -Dconformance.update=true (JobTargetsConformanceTest).");
    ArrayNode out = doc.putArray("cases");
    for (Case c : cases()) {
      ObjectNode item = out.addObject();
      item.put("name", c.name());
      ObjectNode setup = item.putObject("setup");
      ObjectNode settings = setup.putObject("settings");
      c.settings().forEach(settings::put);
      ArrayNode handlers = setup.putArray("handlers");
      c.handlers().forEach(handlers::add);
      ArrayNode jobs = setup.putArray("jobs");
      for (Job j : c.jobs()) {
        ObjectNode n = jobs.addObject();
        n.put("code", j.code());
        if (j.targetUrl() == null) {
          n.putNull("targetUrl");
        } else {
          n.put("targetUrl", j.targetUrl());
        }
      }
      item.set("expect", run(c));
    }
    JsonVectors.check(FILE, doc);
  }

  private ObjectNode run(Case c) {
    ObjectNode expect = JsonVectors.mapper().createObjectNode();
    try (FakePlatform platform = FakePlatform.start()) {
      Map<String, String> env = Receiving.env(platform);
      c.settings().forEach((k, v) -> env.put(k.toUpperCase().replace('.', '_').replace('-', '_'), v));
      Contributor jobs =
          Contributor.of(
              "jobs",
              d -> {
                for (Job j : c.jobs()) {
                  Definition.ScheduledJob base = Definition.ScheduledJob.of(j.code(), j.code(), "0 0 2 * * *");
                  d.scheduledJob(
                      new Definition.ScheduledJob(
                          base.code(), base.name(), null, base.crons(), null, null, null, null, null, null, j.targetUrl()));
                }
              });
      Throwable[] started = new Throwable[1];
      App[] app = new App[1];
      started[0] =
          catchThrowable(
              () ->
                  app[0] =
                      Receiving.start(
                          dir,
                          env,
                          () -> NOW,
                          LocksModule.builder().store("local", LockStores.memory()).build(),
                          Receiving.handlers(
                              r -> c.handlers().forEach(code -> r.onScheduledJob(code, (ctx, f) -> JobResult.success()))),
                          jobs));
      if (c.problem() != null) {
        assertThat(started[0]).as(c.name()).isInstanceOf(StartupException.class);
        assertThat(((StartupException) started[0]).problems().toString()).as(c.name()).contains(c.problem());
        expect.put("problem", c.problem());
        return expect;
      }
      assertThat(started[0]).as(c.name()).isNull();
      try (App a = app[0]) {
        a.get(DefinitionSync.class).run(Mode.APPLY);
        JsonNode sent =
            platform.syncRequests().stream()
                .filter(r -> r.isSyncOf("scheduled-jobs"))
                .findFirst()
                .orElseThrow()
                .json()
                .get("jobs");
        List<String> got = new ArrayList<>();
        ArrayNode targets = expect.putArray("targets");
        for (JsonNode j : sent) {
          String t = j.has("targetUrl") && !j.get("targetUrl").isNull() ? j.get("targetUrl").asString() : null;
          got.add(t);
          if (t == null) {
            targets.addNull();
          } else {
            targets.add(t);
          }
        }
        assertThat(got).as(c.name()).isEqualTo(c.expectTargets());
      }
    }
    return expect;
  }
}
