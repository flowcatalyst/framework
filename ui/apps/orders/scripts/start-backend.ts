// Starts the real orders demo for the Playwright suite (docs/ui/u3.md): the Java application
// (examples/orders-demo, OrdersE2e in its tests) serving the SPA that `vite build` just wrote to
// examples/orders-demo/web/dist, against Testcontainers PostgreSQL and a test OIDC provider.
//
//   node scripts/start-backend.ts --port 4392 --control 4393
//
// 1. `./mvnw test-compile` for the demo and what it depends on (Maven copies web/dist into the
//    classes under fw-spa) and the test classpath is written to a file;
// 2. `java ... OrdersE2e`, which prints READY. Set MAVEN_ARGS=-o to build offline.
// SIGTERM stops the JVM (its shutdown hook stops the servers and the database container).
import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";

const { values } = parseArgs({
  options: { port: { type: "string", default: "4392" }, control: { type: "string", default: "4393" } },
});
const repo = resolve(import.meta.dirname, "../../../..");
const demo = join(repo, "examples/orders-demo");
const classpathFile = join(mkdtempSync(join(tmpdir(), "orders-e2e-")), "classpath.txt");

const mvn = spawnSync(
  join(repo, "mvnw"),
  [
    "-q",
    "-pl",
    "examples/orders-demo",
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
  ["-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-cp", classpath, "io.flowcatalyst.framework.examples.ordersdemo.OrdersE2e", values.port, values.control],
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
