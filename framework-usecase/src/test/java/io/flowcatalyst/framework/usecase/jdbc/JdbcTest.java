package io.flowcatalyst.framework.usecase.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.usecase.AggregateId;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

class JdbcTest {

  enum Colour {
    RED,
    GREEN
  }

  record Sku(String value) implements AggregateId {}

  record Item(
      String id,
      int quantity,
      long big,
      boolean flag,
      BigDecimal price,
      Instant at,
      UUID uuid,
      byte[] blob,
      Colour colour,
      Optional<String> note,
      OptionalInt rank) {}

  static PostgresFixture pg;
  static DataSource ds;

  @BeforeAll
  static void start() {
    pg = PostgresFixture.start();
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(pg.jdbcUrl());
    source.setUser(pg.username());
    source.setPassword(pg.password());
    ds = source;
  }

  @AfterAll
  static void stop() {
    pg.close();
  }

  @BeforeEach
  void reset() {
    pg.reset();
    pg.execute(
        """
        CREATE TABLE item (
          id text PRIMARY KEY, quantity int NOT NULL, big bigint NOT NULL, flag boolean NOT NULL,
          price numeric(12,2) NOT NULL, at timestamptz NOT NULL, uuid uuid NOT NULL,
          blob bytea NOT NULL, colour text NOT NULL, note text, rank int
        )
        """);
  }

  static final RowMapper<Item> ITEM =
      row ->
          new Item(
              row.string("id"),
              row.integer("quantity"),
              row.longValue("big"),
              row.bool("flag"),
              row.decimal("price"),
              row.instant("at"),
              row.uuid("uuid"),
              row.bytes("blob"),
              Colour.valueOf(row.string("colour")),
              row.optionalString("note"),
              row.optionalInteger("rank"));

  private void insert(String id, Object note, Object rank) throws SQLException {
    Jdbc.update(
        ds,
        "INSERT INTO item (id, quantity, big, flag, price, at, uuid, blob, colour, note, rank) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        new Sku(id),
        7,
        9_000_000_000L,
        true,
        new BigDecimal("12.34"),
        Instant.parse("2026-10-08T10:15:30.123456Z"),
        UUID.fromString("00000000-0000-0000-0000-00000000000a"),
        new byte[] {1, 2, 3},
        Colour.GREEN,
        note,
        rank);
  }

  @Test
  void everySupportedTypeRoundTripsThroughBindAndMap() throws Exception {
    insert("a", "hello", 5);

    List<Item> items = Jdbc.query(ds, "SELECT * FROM item WHERE id = ?", ITEM, "a");

    assertThat(items).hasSize(1);
    Item item = items.getFirst();
    assertThat(item.id()).isEqualTo("a");
    assertThat(item.quantity()).isEqualTo(7);
    assertThat(item.big()).isEqualTo(9_000_000_000L);
    assertThat(item.flag()).isTrue();
    assertThat(item.price()).isEqualByComparingTo("12.34");
    assertThat(item.at()).isEqualTo(Instant.parse("2026-10-08T10:15:30.123456Z"));
    assertThat(item.uuid()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    assertThat(item.blob()).containsExactly(1, 2, 3);
    assertThat(item.colour()).isEqualTo(Colour.GREEN);
    assertThat(item.note()).contains("hello");
    assertThat(item.rank()).hasValue(5);
  }

  @Test
  void typedNullsAreBoundAndReadBackAsEmpty() throws Exception {
    insert("n", Jdbc.nullOf(Types.VARCHAR), Jdbc.nullOf(Types.INTEGER));

    Item item = Jdbc.queryOne(ds, "SELECT * FROM item WHERE id = ?", ITEM, "n").orElseThrow();

    assertThat(item.note()).isEmpty();
    assertThat(item.rank()).isEmpty();
  }

  @Test
  void aNullInANonOptionalGetterNamesTheColumn() throws Exception {
    insert("n", Jdbc.nullOf(Types.VARCHAR), Jdbc.nullOf(Types.INTEGER));

    Throwable t =
        catchThrowable(
            () -> Jdbc.query(ds, "SELECT note FROM item", row -> row.string("note")));

    assertThat(t).isInstanceOf(IllegalStateException.class).hasMessageContaining("'note'");
  }

  @Test
  void queryOneReturnsEmptyForNoRowsAndFailsForMany() throws Exception {
    assertThat(Jdbc.queryOne(ds, "SELECT * FROM item WHERE id = ?", ITEM, "none")).isEmpty();
    insert("a", "x", 1);
    insert("b", "y", 2);
    assertThatIllegalStateException()
        .isThrownBy(() -> Jdbc.queryOne(ds, "SELECT * FROM item", ITEM))
        .withMessageContaining("2");
  }

  @Test
  void updateReturnsTheRowCount() throws Exception {
    insert("a", "x", 1);
    insert("b", "y", 2);
    assertThat(Jdbc.update(ds, "UPDATE item SET quantity = ?", 1)).isEqualTo(2);
    assertThat(Jdbc.update(ds, "DELETE FROM item WHERE id = ?", "a")).isEqualTo(1);
  }

  @Test
  void identifiersBindByTheirValue() throws Exception {
    insert("c", "x", 1);
    Jdbc.update(ds, "UPDATE item SET note = ? WHERE id = ?", new ClientId("acme"), new Sku("c"));
    assertThat(Jdbc.query(ds, "SELECT note FROM item", row -> row.string("note")))
        .containsExactly("acme");
  }

  @Test
  void theConnectionOverloadsJoinTheCallersTransaction() throws Exception {
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      Jdbc.update(c, "INSERT INTO item (id, quantity, big, flag, price, at, uuid, blob, colour) "
          + "VALUES ('t', 1, 1, true, 1, now(), gen_random_uuid(), '\\x00', 'RED')");
      assertThat(Jdbc.query(c, "SELECT id FROM item", row -> row.string("id"))).containsExactly("t");
      c.rollback();
    }
    assertThat(Jdbc.query(ds, "SELECT id FROM item", row -> row.string("id"))).isEmpty();
  }

  @Test
  void anUnsupportedParameterTypeIsRejectedWithItsPosition() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Jdbc.query(ds, "SELECT ?", row -> 1, "ok", new Object()))
        .withMessageContaining("parameter 2")
        .withMessageContaining("java.lang.Object");
  }

  @Test
  void databaseErrorsKeepTheirSqlState() {
    SQLException e =
        (SQLException) catchThrowable(() -> Jdbc.query(ds, "SELECT * FROM nope", row -> 1));
    assertThat(e.getSQLState()).isEqualTo("42P01");
  }

  @Test
  void connectionsAreReturnedAfterEveryCall() throws Exception {
    com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
    config.setJdbcUrl(pg.jdbcUrl());
    config.setUsername(pg.username());
    config.setPassword(pg.password());
    config.setMaximumPoolSize(1);
    config.setConnectionTimeout(1000);
    try (com.zaxxer.hikari.HikariDataSource pool = new com.zaxxer.hikari.HikariDataSource(config)) {
      for (int i = 0; i < 20; i++) {
        Jdbc.query(pool, "SELECT 1", row -> 1);
        Jdbc.queryOne(pool, "SELECT 1", row -> 1);
        Jdbc.update(pool, "DELETE FROM item WHERE false");
        assertThat(catchThrowable(() -> Jdbc.query(pool, "SELECT * FROM nope", row -> 1)))
            .isInstanceOf(SQLException.class);
      }
    }
  }
}
