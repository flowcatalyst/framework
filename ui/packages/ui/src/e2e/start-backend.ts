// Starts an application's Java launcher (an `E2eLauncher` main class in the application's tests) for a
// Playwright suite: the application serving the SPA that `vite build` just wrote to its `web/dist`,
// against Testcontainers PostgreSQL and a test OIDC provider.
//
// 1. `./mvnw test-compile` for the application's module and what it depends on (Maven copies web/dist
//    into the classes under fw-spa) and the test classpath is written to a file;
// 2. `java ... <mainClass> <port> <control> [<public>]`, which prints READY. Set MAVEN_ARGS=-o to build
//    offline.
// SIGTERM stops the JVM (its shutdown hook stops the servers and the database container).
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";

export interface BackendOptions {
  /** The application's Maven module, relative to the repository root: `examples/orders-demo`. */
  readonly module: string;
  /** The launcher's fully qualified main class. */
  readonly mainClass: string;
  /** The application's port. */
  readonly port: number;
  /** The control server's port. */
  readonly control: number;
  /** The origin the browser sees when something else fronts the backend (the Vite dev server). */
  readonly public?: string;
  /** The Maven repository root; default: the nearest directory above the working directory holding `mvnw`. */
  readonly root?: string;
}

/** The nearest directory from `start` upwards that holds the Maven wrapper. */
export function findRepositoryRoot(start: string): string {
  for (let dir = resolve(start); ; dir = dirname(dir)) {
    if (existsSync(join(dir, "mvnw"))) return dir;
    if (dirname(dir) === dir) throw new Error(`no mvnw above ${start}: pass root`);
  }
}

/** The Maven arguments: compile the module's tests and what it needs, and write the test classpath. */
export function mavenArguments(module: string, classpathFile: string): string[] {
  return ["-q", "-pl", module, "-am", "test-compile", "dependency:build-classpath", `-Dmdep.outputFile=${classpathFile}`];
}

/** The JVM arguments that run the launcher. */
export function javaArguments(options: BackendOptions, classpath: string): string[] {
  return [
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
    "-cp",
    classpath,
    options.mainClass,
    String(options.port),
    String(options.control),
    ...(options.public === undefined ? [] : [options.public]),
  ];
}

/** Builds the application and runs its launcher until a signal stops it; the process exits with the JVM's code. */
export function startBackend(options: BackendOptions): void {
  const repo = options.root ?? findRepositoryRoot(process.cwd());
  const module = join(repo, options.module);
  const classpathFile = join(mkdtempSync(join(tmpdir(), "fc-e2e-")), "classpath.txt");

  const mvn = spawnSync(join(repo, "mvnw"), mavenArguments(options.module, classpathFile), { cwd: repo, stdio: "inherit" });
  if (mvn.status !== 0) {
    console.error("the Maven build failed");
    process.exit(mvn.status ?? 1);
  }

  const classpath = [join(module, "target/classes"), join(module, "target/test-classes"), readFileSync(classpathFile, "utf8").trim()].join(":");
  const java = process.env["JAVA_HOME"] === undefined ? "java" : join(process.env["JAVA_HOME"], "bin/java");
  const child = spawn(java, javaArguments(options, classpath), { cwd: module, stdio: "inherit" });
  for (const signal of ["SIGTERM", "SIGINT"] as const) {
    process.on(signal, () => {
      child.kill("SIGTERM");
    });
  }
  child.on("exit", (code) => {
    process.exit(code ?? 0);
  });
}
