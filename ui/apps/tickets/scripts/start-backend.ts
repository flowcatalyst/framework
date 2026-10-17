// Starts the real tickets service for the Playwright suite (docs/m4f-trial-service.md): the Java application
// (examples/tickets-trial, TicketsE2e in its tests) serving the SPA that `vite build` just wrote to
// examples/tickets-trial/web/dist, against Testcontainers PostgreSQL and a test OIDC provider.
//
//   node scripts/start-backend.ts --port 4394 --control 4395 [--public http://127.0.0.1:5175]
//
// 1. `./mvnw test-compile` for the service and what it depends on (Maven copies web/dist into the
//    classes under fw-spa) and the test classpath is written to a file;
// 2. `java ... TicketsE2e`, which prints READY. Set MAVEN_ARGS=-o to build offline.
// SIGTERM stops the JVM (its shutdown hook stops the servers and the database container).
import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";

const { values } = parseArgs({
  options: {
    port: { type: "string", default: "4394" },
    control: { type: "string", default: "4395" },
    // The origin the browser sees when something else fronts the backend (the Vite dev server).
    public: { type: "string" },
  },
});
const repo = resolve(import.meta.dirname, "../../../..");
const demo = join(repo, "examples/tickets-trial");
const classpathFile = join(mkdtempSync(join(tmpdir(), "tickets-e2e-")), "classpath.txt");

const mvn = spawnSync(
  join(repo, "mvnw"),
  [
    "-q",
    "-pl",
    "examples/tickets-trial",
    "-am",
    "test-compile",
    "dependency:build-classpath",
    `-Dmdep.outputFile=${classpathFile}`,
    "-Dmdep.includeScope=test",
  ],
  { cwd: repo, stdio: "inherit" },
);
if (mvn.status !== 0) {
  console.error("the Maven build failed");
  process.exit(mvn.status ?? 1);
}

const classpath = [join(demo, "target/classes"), join(demo, "target/test-classes"), readFileSync(classpathFile, "utf8").trim()].join(":");
const java = process.env["JAVA_HOME"] === undefined ? "java" : join(process.env["JAVA_HOME"], "bin/java");
const child = spawn(
  java,
  ["-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-cp", classpath, "io.flowcatalyst.framework.examples.ticketstrial.TicketsE2e", values.port, values.control, ...(values.public === undefined ? [] : [values.public])],
  { cwd: demo, stdio: "inherit" },
);
for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.on(signal, () => {
    child.kill("SIGTERM");
  });
}
child.on("exit", (code) => {
  process.exit(code ?? 0);
});
