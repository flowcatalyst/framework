# Contract: build, BOM and publishing (MA item 1)

Status: built 2026-10-10. Nothing has been published: the configuration and the workflow are ready,
and the first `v*` tag publishes. No credentials are in the repository.

## 1. What an application needs

An application outside this repository needs **only**:

1. the `framework-bom` import in `dependencyManagement`, at the framework's version;
2. the framework modules it uses, without versions;
3. Java 25 (`maven.compiler.release` 25) and a surefire that runs JUnit 6 (3.x; the framework's own
   build uses 3.6.0);
4. a repository that serves the artifacts (GitHub Packages, section 3, or the local repository after
   `./mvnw install`; note that the poms carry `0.1.0-SNAPSHOT` even at a release tag, section 3).

It does **not** need `framework-parent`, Error Prone, NullAway or `.mvn/jvm.config`. Those are the
framework's own build strictness; the framework jars are ordinary Java 25 bytecode. (The first
application copied them because the stop-gap told it to import `framework-parent`.) An application
may still choose Error Prone and NullAway; the guide shows how, as an option.

`examples/locks-demo` is built in the reactor **shaped exactly like such an application**: no
parent, the BOM import, the compiler and surefire plugins, nothing else. If the BOM stops being
enough, the framework's own build fails.

## 2. `framework-bom`

- Artifact `io.flowcatalyst.framework:framework-bom`, packaging `pom`, **no parent**. A BOM that
  inherited `framework-parent` would export whatever the parent's `dependencyManagement` holds and
  tie its version to the parent; stand-alone, what an application imports is exactly this file.
- Manages: every framework artifact at the BOM's own version (`${project.version}`), the published
  test-jars (`framework-database`, `framework-redis`, `framework-locks`, `framework-flowcatalyst`,
  `<type>test-jar</type>`, scope `test`), and the versions of every library the framework artifacts
  are built and tested with: jspecify, slf4j-api and slf4j-simple, snakeyaml-engine, HikariCP, the
  PostgreSQL driver, Jedis, Jackson 3 (`jackson-databind`), Jetty (`jetty-server`), the FlowCatalyst
  Java SDK, AssertJ, and the JUnit and Testcontainers BOMs (imported), because the published
  test-jars are compiled against them. Libraries added by later items (Vert.x for the HTTP client)
  are added here.
- `framework-parent` imports `framework-bom` (scope `import`, `${project.version}`). The BOM is the
  **one place versions live**; the parent keeps only build-plugin versions. Maven 3.9 resolves the
  import from the reactor (checked against an empty local repository), so no install step is needed.
  If the two versions ever differ the import cannot resolve and the build fails.
- The Maven plugin `framework-sql-maven-plugin` cannot be managed by a BOM (plugins are not
  imported); an application writes its version, normally a `framework.version` property.

## 3. Publishing (GitHub Packages)

- `distributionManagement` in `framework-parent` and in `framework-bom` (which does not inherit it):
  repository id `github`, URL `https://maven.pkg.github.com/flowcatalyst/framework`.
- Published: `framework-parent`, `framework-bom`, every `framework-*` jar with its sources jar
  (profile `release`, `maven-source-plugin` `jar-no-fork`), the four test-jars, and
  `framework-sql-maven-plugin`. Not published: the examples (`maven.deploy.skip=true`).
- Workflow `.github/workflows/publish.yml`, on a pushed tag `v<major>.<minor>.<patch>[-qualifier]`:
  JDK 25 (Temurin) with a `github` server entry from the workflow's own `GITHUB_TOKEN`
  (permissions `contents: read`, `packages: write`); builds and installs the FlowCatalyst Java SDK
  from the platform repository's `java-sdk/v<sdk version>` tag (section 4); sets every module's
  version from the tag with `versions-maven-plugin:2.19.1:set -DprocessAllModules=true
  -DgenerateBackupPoms=false` (this moves the BOM, the parent, every module and the example's BOM
  import together, checked on a copy of the repository); then `./mvnw -B -Prelease deploy`, which
  runs the whole build including tests (Docker is available on GitHub's Ubuntu runners) before
  anything is uploaded.
- Versions on `main` stay `-SNAPSHOT`; the tag's version is set only inside the workflow run and is
  not committed back.
- Reading the packages: GitHub Packages requires authentication for Maven reads even for public
  packages. A consuming build needs a `settings.xml` server `github` with a username and a token
  that has `read:packages` (in CI, the workflow's `GITHUB_TOKEN` when the consumer is in the same
  organisation and has been granted access). The guide shows both.

## 4. The FlowCatalyst Java SDK dependency

`framework-flowcatalyst` depends on `io.flowcatalyst:flowcatalyst-sdk`, which no remote repository
serves (STATUS). The workflow builds it to compile the framework and does **not** publish it. So an
application that uses `framework-flowcatalyst` from GitHub Packages still has to install the SDK
itself (getting started, step 1) until the SDK is published somewhere.

## Rulings pending owner

1. **Publishing the SDK.** Either the platform repository publishes `flowcatalyst-sdk` (preferred:
   it owns the artifact), or this workflow also deploys the SDK jar it builds into this repository's
   package registry. The conservative choice taken: neither; documented.
2. **Group id and naming**: settled 2026-10-21, kept as published: group id `io.flowcatalyst.framework`,
   artifacts `framework-*`. Consumers build against these now.
3. **First version number**: settled 2026-10-21. `v0.1.0` was tagged first and its publish run failed
   midway (framework-http's rate-limit conformance test depended on the host having an `eth0` network
   interface), leaving ten modules uploaded as 0.1.0; **0.1.0 is incomplete, do not use it**. The release is
   `v0.1.1`, which published all 27 artifacts. The workflow now runs `verify` (everything built and tested)
   before a separate `deploy -DskipTests`, and a `workflow_dispatch` dry run builds and tests without
   uploading, so a test failure cannot leave a half-published version again.
4. **Javadoc jars** are not produced (GitHub Packages does not require them; Maven Central would).
