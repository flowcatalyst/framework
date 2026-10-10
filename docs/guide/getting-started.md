<!-- types: AppBuilder, App, Module, Launch -->
# Getting started

> **Status: a BOM, publishing ready, nothing published yet.** An application imports
> `framework-bom` and needs no framework parent and no special compiler setup. The artifacts will be
> served by GitHub Packages from the first `v*` tag (the workflow is in the repository); until then,
> build the framework into your local Maven repository (step 1). See [STATUS](STATUS.md).

## Prerequisites

| Need | For | Notes |
|---|---|---|
| JDK 25 | everything | the build sets `maven.compiler.release` to 25 |
| Maven 3.9 | everything | the repository ships a wrapper (`./mvnw`, Maven 3.9.13); your application can use its own |
| Docker | tests, and `generate`/`check` of [checked SQL](checked-sql.md) | tests start PostgreSQL 18 (and Redis) with Testcontainers; checked SQL starts a throwaway `postgres:18-alpine` unless you point it at an empty database |
| PostgreSQL | running an application | the only database system supported. Several named instances may be configured; use cases and migrations run on the default one |
| Redis or Valkey | only the `redis` lock driver | optional |
| A FlowCatalyst platform | only the [flowcatalyst module](flowcatalyst-module.md) | optional; the module can be disabled |

## 1. Install the framework into your local repository

```
git clone https://github.com/flowcatalyst/framework.git
cd framework
./mvnw install
```

This runs the whole build, including the tests (which need Docker). To skip the tests:
`./mvnw install -DskipTests`. Note that `examples/orders-demo` runs its checked-SQL `check` goal in
`verify`, so it needs Docker even then. To leave the examples out:
`./mvnw install -DskipTests -pl '!examples/locks-demo,!examples/orders-demo'`.

The artifacts get group id `io.flowcatalyst.framework` and version `0.1.0-SNAPSHOT`.

The module `framework-flowcatalyst` depends on `io.flowcatalyst:flowcatalyst-sdk:0.0.11` (the
FlowCatalyst Java SDK). **That artifact is not published to Maven Central or to any other remote
repository.** Its source is `clients/java-sdk` in the platform repository,
`github.com/flowcatalyst/flowcatalyst`; that repository has no workflow that publishes it (nor has `flowcatalyst-javalin`, where the
SDK is mirrored from), its `pom.xml` has no `distributionManagement`, and `make release-java-sdk`
only bumps the version and pushes a `java-sdk/vX.Y.Z` tag. So the only way to get it is to build it into your local Maven
repository **before** step 1 (JDK 25 and Maven; the build downloads its own dependencies from Maven
Central):

```
git clone https://github.com/flowcatalyst/flowcatalyst.git
cd flowcatalyst
git checkout java-sdk/v0.0.11
cd clients/java-sdk
mvn install -DskipTests
```

This installs `io.flowcatalyst:flowcatalyst-sdk:0.0.11` into `~/.m2/repository`. (Checked by
extracting that tag and running `mvn package -DskipTests` against an empty local repository, which
produced `flowcatalyst-sdk-0.0.11.jar`; `install` is the same build plus the copy.) The version has
to match `flowcatalyst-sdk.version` in `framework-bom/pom.xml`. Drop `-DskipTests` to run
its tests too. A repository (GitHub Packages, an internal Nexus) that serves the SDK works equally:
the framework does not care where the artifact comes from.

## 2. Import `framework-bom`

`framework-bom` manages the version of every framework module and of every library they are built
and tested with (Jackson 3, Jetty, HikariCP, the PostgreSQL driver, JUnit, Testcontainers, and so
on). This is `examples/locks-demo`'s `pom.xml`, which the framework's build compiles and tests
**without** any framework parent, so this is all an application needs (also checked outside the
repository: a copy of `locks-demo` with no `.mvn/jvm.config`, built offline against `./mvnw install`ed
artifacts, compiled and passed its tests):

```xml
<properties>
  <maven.compiler.release>25</maven.compiler.release>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.flowcatalyst.framework</groupId>
      <artifactId>framework-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<!-- The plugin snippet: Java 25 and a surefire that runs JUnit 6. Nothing else is required. -->
<build>
  <plugins>
    <plugin>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.16.0</version>
    </plugin>
    <plugin>
      <artifactId>maven-surefire-plugin</artifactId>
      <version>3.6.0</version>
    </plugin>
  </plugins>
</build>
```

Then list the modules you use, without versions:

```xml
<dependencies>
  <dependency>
    <groupId>io.flowcatalyst.framework</groupId>
    <artifactId>framework-usecase</artifactId>      <!-- write path; brings framework-database and framework-core -->
  </dependency>
  <dependency>
    <groupId>io.flowcatalyst.framework</groupId>
    <artifactId>framework-http-jetty</artifactId>   <!-- HTTP server; brings framework-http -->
  </dependency>
  <dependency>
    <groupId>io.flowcatalyst.framework</groupId>
    <artifactId>framework-flowcatalyst</artifactId> <!-- optional: platform client and sync -->
  </dependency>
  <dependency>
    <groupId>org.jspecify</groupId>
    <artifactId>jspecify</artifactId>
  </dependency>
  <!-- An application chooses its logging backend; the framework brings none. -->
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <scope>runtime</scope>
  </dependency>
  <!-- Tests: versions from the BOM too. -->
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

The checked-SQL Maven plugin is a plugin, which a BOM cannot manage: give it the framework's version
yourself (`<version>0.1.0-SNAPSHOT</version>`, or a `framework.version` property you also use for the
BOM); see [Checked SQL](checked-sql.md).

The modules, and what each is for:

| Artifact | Module class you list | Page |
|---|---|---|
| `framework-core` | none; it is always present (lifecycle, config, `Context`, health) | [lifecycle](modules-and-lifecycle.md), [config](config.md), [context](context.md) |
| `framework-database` | `DatabaseModule` | [lifecycle](modules-and-lifecycle.md), [write path](write-path.md) |
| `framework-redis` | `RedisModule` | [locks](locks.md) |
| `framework-locks` | `LocksModule` (memory driver) | [locks](locks.md) |
| `framework-locks-database` | `LocksDatabaseModule` | [locks](locks.md) |
| `framework-locks-redis` | `LocksRedisModule` | [locks](locks.md) |
| `framework-usecase` | `OutboxModule`, `UseCaseModule` | [write path](write-path.md) |
| `framework-http` | none; the HTTP types and `TestClient`, no server. Depends on `framework-core` and Jackson only ([what it pulls in](http.md#what-framework-http-pulls-in)) | [http](http.md) |
| `framework-http-jetty` | `JettyHttpModule` | [http](http.md) |
| `framework-httpclient` | none; the HTTP client library (Vert.x), usable without the framework | [HTTP client](http-client.md) |
| `framework-httpclient-module` | `HttpClientModule` | [HTTP client](http-client.md) |
| `framework-flowcatalyst` | `FlowCatalystModule` | [flowcatalyst module](flowcatalyst-module.md) |
| `framework-sql`, `framework-sql-maven-plugin` | none; the checked-SQL generator and its Maven plugin | [checked SQL](checked-sql.md) |

Test support: `TestClient` is in `framework-http`'s ordinary jar; the fixtures are published as
test-jars (`<type>test-jar</type>`, `<scope>test</scope>`) of `framework-database`,
`framework-redis`, `framework-locks` and `framework-flowcatalyst`; the BOM manages them.
See [Testing](testing.md).

## 3. Getting the artifacts from GitHub Packages

Once a version is tagged (`v0.1.0`), the publish workflow (`.github/workflows/publish.yml`) deploys
every framework artifact, the BOM, the parent and the checked-SQL plugin to
`https://maven.pkg.github.com/flowcatalyst/framework`. **Nothing has been published yet.** To read
from it, add the repository to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/flowcatalyst/framework</url>
  </repository>
</repositories>
<pluginRepositories>
  <pluginRepository>
    <id>github</id>
    <url>https://maven.pkg.github.com/flowcatalyst/framework</url>
  </pluginRepository>
</pluginRepositories>
```

GitHub Packages needs a token for Maven reads, even for public packages. In `~/.m2/settings.xml`
(never in the project):

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USER</username>
      <password>${env.GITHUB_TOKEN}</password>   <!-- a token with read:packages -->
    </server>
  </servers>
</settings>
```

In GitHub Actions, `actions/setup-java` writes that entry for you (`server-id: github`,
`server-username: GITHUB_ACTOR`, `server-password: GITHUB_TOKEN`); the job needs
`permissions: packages: read` and access to the package.

`framework-flowcatalyst` still needs the FlowCatalyst Java SDK in your repository (step 1): the
publish workflow builds it to compile the framework but does not publish it (an owner decision,
[contracts/build.md](../contracts/build.md)).

**Releasing** (maintainers): push a tag `v<major>.<minor>.<patch>`. The workflow sets every
module's version from the tag (`versions-maven-plugin:2.19.1:set -DprocessAllModules=true`), runs
the full build with tests, and deploys with `-Prelease` (adds sources jars). The same by hand, with a
`github` server entry in your settings:

```
./mvnw -B org.codehaus.mojo:versions-maven-plugin:2.19.1:set -DnewVersion=0.1.0 -DprocessAllModules=true -DgenerateBackupPoms=false
./mvnw -B -Prelease deploy
git checkout -- '*pom.xml'      # main stays on -SNAPSHOT
```

### Optional: Error Prone and NullAway

The framework's own build runs Error Prone with NullAway as an error, and every framework package
is `@NullMarked`. **An application does not need either.** If you want the same checks on your
code, this is the setup from the framework's root `pom.xml`: put `@NullMarked` (from
`org.jspecify.annotations`) in a `package-info.java` for each of your packages, and change
`AnnotatedPackages` to your own package root.

```xml
<properties>
  <errorprone.version>2.50.0</errorprone.version>
  <nullaway.version>0.14.2</nullaway.version>
  <maven-compiler-plugin.version>3.16.0</maven-compiler-plugin.version>
</properties>

<build>
  <plugins>
    <plugin>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>${maven-compiler-plugin.version}</version>
      <configuration>
        <release>${maven.compiler.release}</release>
        <showWarnings>true</showWarnings>
        <annotationProcessorPaths>
          <path>
            <groupId>com.google.errorprone</groupId>
            <artifactId>error_prone_core</artifactId>
            <version>${errorprone.version}</version>
          </path>
          <path>
            <groupId>com.uber.nullaway</groupId>
            <artifactId>nullaway</artifactId>
            <version>${nullaway.version}</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
      <executions>
        <execution>
          <id>default-compile</id>
          <configuration>
            <compilerArgs>
              <arg>-XDcompilePolicy=simple</arg>
              <arg>--should-stop=ifError=FLOW</arg>
              <arg>-Xlint:all,-processing</arg>
              <arg>-Xplugin:ErrorProne -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages=com.acme -XepOpt:NullAway:JSpecifyMode=true</arg>
            </compilerArgs>
          </configuration>
        </execution>
        <execution>
          <id>default-testCompile</id>
          <configuration>
            <compilerArgs>
              <arg>-XDcompilePolicy=simple</arg>
              <arg>--should-stop=ifError=FLOW</arg>
              <arg>-Xlint:all,-processing</arg>
              <!-- Error Prone runs on tests; NullAway does not (test fixtures use late-initialised fields). -->
              <arg>-Xplugin:ErrorProne -Xep:NullAway:OFF</arg>
            </compilerArgs>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

Error Prone on JDK 25 also needs these flags in the JVM that runs Maven. Without them the compile
fails with only `An unknown compilation problem occurred`. Put them in `.mvn/jvm.config` at your
project root (this is the framework's own file, verbatim):

```
--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
```

Without them, what you give up: the framework's own build checks nullness at compile time, and its
`@NullMarked` annotations tell your IDE and any checker you run what can be null, but without
NullAway in *your* build nothing stops you passing a `null` to a method that says it never takes
one, and a mistake there surfaces at run time.

## 4. A first application

Write a module, list it, start it. The examples show real ones:

- [`examples/locks-demo`](https://github.com/flowcatalyst/framework/tree/main/examples/locks-demo):
  database, Redis and memory lock instances, and a `config/application.yaml`.
- [`examples/orders-demo`](https://github.com/flowcatalyst/framework/tree/main/examples/orders-demo):
  an aggregate with lines, three use cases, optimistic locking, checked SQL, and a test that
  exercises all of it against PostgreSQL.

Continue with [Modules and lifecycle](modules-and-lifecycle.md) and [Config](config.md). For
development any PostgreSQL 18 will do: put its URL and user in `config/application.yaml` and the
password in `config/secrets.local.yaml` (see [Config](config.md#secrets)).
