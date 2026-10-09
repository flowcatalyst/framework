<!-- types: AppBuilder, App, Module, Launch -->
# Getting started

> **Status: no published artifacts, no BOM.** Today you build the framework from source into your
> local Maven repository and depend on it from there. The `framework-parent` import and the
> compiler setup below are a **stop-gap** until a `framework-bom` and published artifacts exist
> (plan item MA-1, see [STATUS](STATUS.md)). Expect these steps to change.

## Prerequisites

| Need | For | Notes |
|---|---|---|
| JDK 25 | everything | the build sets `maven.compiler.release` to 25 |
| Maven 3.9 | everything | the repository ships a wrapper (`./mvnw`, Maven 3.9.13); your application can use its own |
| Docker | tests, and `generate`/`check` of [checked SQL](checked-sql.md) | tests start PostgreSQL 18 (and Redis) with Testcontainers; checked SQL starts a throwaway `postgres:18-alpine` unless you point it at an empty database |
| PostgreSQL | running an application | the only database supported |
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
FlowCatalyst Java SDK). **Not verified:** whether that artifact resolves from a remote repository
on a clean machine; on the author's machine it sits in the local repository (installed, not
downloaded). If `install` cannot resolve it, build and install the SDK from the platform
repository's `clients/java-sdk` first.

## 2. Import `framework-parent` as a BOM

`framework-parent` carries a `dependencyManagement` section with the versions of every framework
module and of every library they use (Jackson 3, Jetty, HikariCP, JUnit, Testcontainers, and so
on). Importing it gives your application those versions. This snippet was compiled against the
installed artifacts:

```xml
<properties>
  <maven.compiler.release>25</maven.compiler.release>
  <framework.version>0.1.0-SNAPSHOT</framework.version>
</properties>

<dependencyManagement>
  <dependencies>
    <!-- Stop-gap BOM: framework-parent's dependencyManagement, imported. -->
    <dependency>
      <groupId>io.flowcatalyst.framework</groupId>
      <artifactId>framework-parent</artifactId>
      <version>${framework.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
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
</dependencies>
```

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
| `framework-http` | none; the HTTP types and `TestClient`, no server (it depends on `framework-usecase`) | [http](http.md) |
| `framework-http-jetty` | `JettyHttpModule` | [http](http.md) |
| `framework-flowcatalyst` | `FlowCatalystModule` | [flowcatalyst module](flowcatalyst-module.md) |
| `framework-sql`, `framework-sql-maven-plugin` | none; the checked-SQL generator and its Maven plugin | [checked SQL](checked-sql.md) |

Test support is published as test-jars (`<type>test-jar</type>`, `<scope>test</scope>`) of
`framework-database`, `framework-redis`, `framework-locks` and `framework-flowcatalyst`; the parent
already manages them. See [Testing](testing.md).

## 3. Compiler setup: Error Prone and NullAway (stop-gap)

The framework's own build runs Error Prone with NullAway as an error, and every framework package
is `@NullMarked`. A consuming application is not forced to do the same, but the first application
copied this setup, so it is recorded here as it appears in the framework's root `pom.xml`. Put
`@NullMarked` (from `org.jspecify.annotations`) in a `package-info.java` for each of your packages,
and change `AnnotatedPackages` to your own package root.

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

You may leave Error Prone and NullAway out of your own build; nothing in the framework requires
them.

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
