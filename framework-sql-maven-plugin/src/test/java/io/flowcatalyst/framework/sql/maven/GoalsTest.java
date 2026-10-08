package io.flowcatalyst.framework.sql.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.database.PostgresFixture;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The goals as Maven would run them, on a small project and a real PostgreSQL. */
class GoalsTest {

  static PostgresFixture pg;

  @TempDir Path dir;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void setUp() throws IOException {
    pg.reset();
    Files.createDirectories(dir.resolve("src/main/sql/demo"));
    Files.createDirectories(dir.resolve("src/main/resources/db/demo"));
    Files.writeString(
        dir.resolve("src/main/resources/db/demo/V0001__t.sql"),
        "CREATE TABLE t (id text PRIMARY KEY, n int NOT NULL, note text);");
    Files.writeString(
        dir.resolve("src/main/sql/demo/TQueries.sql"),
        """
        -- name: byId :one
        SELECT id, n, note FROM t WHERE id = :id;
        """);
  }

  private <M extends SqlMojoSupport> M configure(M mojo) {
    MavenProject project = new MavenProject();
    project.setFile(dir.resolve("pom.xml").toFile());
    project.getBuild().setOutputDirectory(dir.resolve("target/classes").toString());
    Resource resources = new Resource();
    resources.setDirectory(dir.resolve("src/main/resources").toString());
    project.addResource(resources);
    mojo.project = project;
    mojo.sqlDirectory = dir.resolve("src/main/sql").toFile();
    mojo.outputDirectory = dir.resolve("src/main/java-generated").toFile();
    Migration m = new Migration();
    m.moduleId = "demo";
    m.location = "db/demo";
    mojo.migrations = List.of(m);
    mojo.databaseUrl = pg.jdbcUrl();
    mojo.databaseUser = pg.username();
    mojo.databasePassword = pg.password();
    mojo.image = "postgres:18-alpine";
    return mojo;
  }

  private void generate() throws Exception {
    configure(new GenerateMojo()).execute();
  }

  private MojoFailureException check() {
    pg.reset();
    Throwable t = catchThrowable(() -> configure(new CheckMojo()).execute());
    return t instanceof MojoFailureException f ? f : null;
  }

  @Test
  void generateWritesTheSourceAndAddsItsRootAndCheckPassesOnIt() throws Exception {
    GenerateMojo mojo = configure(new GenerateMojo());
    mojo.execute();

    Path generated = dir.resolve("src/main/java-generated/demo/TQueries.java");
    assertThat(generated).exists();
    assertThat(Files.readString(generated))
        .startsWith("// Generated from src/main/sql/demo/TQueries.sql. Do not edit.\n")
        .contains("public static Optional<ByIdRow> byId(");
    assertThat(mojo.project.getCompileSourceRoots())
        .contains(dir.resolve("src/main/java-generated").toString());

    assertThat(check()).isNull();
  }

  @Test
  void checkNamesTheFileWhenItWasEditedOrAQueryChangedOrASourceIsGone() throws Exception {
    generate();
    Path generated = dir.resolve("src/main/java-generated/demo/TQueries.java");
    String original = Files.readString(generated);

    Files.writeString(generated, original + "// edited\n");
    assertThat(check()).hasMessageContaining("src/main/java-generated/demo/TQueries.java").hasMessageContaining("differs");
    Files.writeString(generated, original);
    assertThat(check()).isNull();

    Path sql = dir.resolve("src/main/sql/demo/TQueries.sql");
    String query = Files.readString(sql);
    Files.writeString(sql, query.replace("SELECT id, n, note", "SELECT id, n"));
    assertThat(check()).hasMessageContaining("src/main/java-generated/demo/TQueries.java").hasMessageContaining("differs");
    Files.writeString(sql, query);

    Files.delete(sql);
    assertThat(check()).hasMessageContaining("src/main/java-generated/demo/TQueries.java").hasMessageContaining("no query file");
  }

  @Test
  void aBrokenQueryFailsTheGoalNamingTheFileAndLine() throws Exception {
    Files.writeString(
        dir.resolve("src/main/sql/demo/TQueries.sql"),
        "-- name: ok :one\nSELECT id FROM t;\n\n-- name: broken :many\nSELECT missing FROM t;\n");

    Throwable t = catchThrowable(this::generate);

    assertThat(t).isInstanceOf(MojoFailureException.class);
    assertThat(t.getMessage())
        .contains("src/main/sql/demo/TQueries.sql:4:")
        .contains("query 'broken'")
        .contains("column \"missing\" does not exist");
    assertThat(dir.resolve("src/main/java-generated")).doesNotExist();
  }

  @Test
  void aProjectWithoutQueriesDoesNothingAndNeedsNoDatabase() throws Exception {
    Files.delete(dir.resolve("src/main/sql/demo/TQueries.sql"));
    GenerateMojo mojo = configure(new GenerateMojo());
    mojo.databaseUrl = "jdbc:postgresql://127.0.0.1:1/none";

    mojo.execute();

    assertThat(new File(dir.toFile(), "src/main/java-generated")).doesNotExist();
  }

  @Test
  void addSourceNeedsNoDatabase() throws Exception {
    AddSourceMojo mojo = configure(new AddSourceMojo());
    mojo.databaseUrl = "jdbc:postgresql://127.0.0.1:1/none";

    mojo.execute();

    assertThat(mojo.project.getCompileSourceRoots()).contains(dir.resolve("src/main/java-generated").toString());
  }

  @Test
  void aMigrationThatDoesNotApplyFailsTheGoalWithTheMigrationsMessage() throws Exception {
    Files.writeString(dir.resolve("src/main/resources/db/demo/V0002__bad.sql"), "ALTER TABLE nope ADD COLUMN x int;");

    Throwable t = catchThrowable(this::generate);

    assertThat(t).isInstanceOf(MojoFailureException.class).hasMessageContaining("V0002__bad.sql");
  }
}
