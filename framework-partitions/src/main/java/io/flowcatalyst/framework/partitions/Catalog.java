package io.flowcatalyst.framework.partitions;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads of the PostgreSQL catalog: a table's partitions, its shape (start-up validation) and the detached tables. */
final class Catalog {

  private Catalog() {}

  /** A relation attached to a parent. */
  record Child(String schema, String name) {}

  /** What validation found: the problems, and the resolved schema and name when the table exists. */
  record Validation(List<String> problems, Optional<String[]> names) {}

  /** The attached partitions of the parent, from the catalog. */
  static List<Child> children(Connection c, Target tg) throws SQLException {
    return Sql.query(
        c,
        """
        SELECT n.nspname, c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE i.inhparent = ?::regclass""",
        rs -> new Child(rs.getString(1), rs.getString(2)),
        tg.ident());
  }

  /** Our names among the children, and the strays (another shape, another schema). */
  record Ours(Map<String, Instant> starts, List<Child> strays) {}

  static Ours ours(Target tg, List<Child> kids) {
    Map<String, Instant> starts = new HashMap<>();
    List<Child> strays = new ArrayList<>();
    for (Child c : kids) {
      if (!c.schema().equals(tg.schema)) {
        strays.add(c);
        continue;
      }
      Optional<Instant> start = tg.parse(c.name());
      if (start.isEmpty()) {
        strays.add(c);
        continue;
      }
      starts.put(c.name(), start.get());
    }
    return new Ours(starts, strays);
  }

  /** The plain tables of the parent's schema that carry the detach mark: recovery candidates. */
  static List<String> detachedTables(Connection c, Target tg) throws SQLException {
    return Sql.query(
        c,
        """
        SELECT c.relname FROM pg_class c
        WHERE c.relnamespace = ?::regnamespace AND c.relkind = 'r' AND NOT c.relispartition AND c.relname LIKE ?
          AND obj_description(c.oid, 'pg_class') = ?""",
        rs -> rs.getString(1),
        Sql.quote(tg.schema),
        tg.rel + "\\_p%",
        tg.detachMark());
  }

  private record Shape(
      String kind,
      int natts,
      int attnum,
      String attname,
      boolean timestamptz,
      boolean text,
      boolean date,
      String collation,
      String columnCollation,
      String keydef) {}

  /**
   * Resolves the table's schema and name and checks its shape against the declaration. The messages say
   * what DDL is expected.
   */
  static Validation validate(Connection c, Spec spec) throws SQLException {
    Table table = spec.table();
    String schemaIn = table.schemaPart().orElse("");
    String relIn = table.tablePart().orElse("");
    String qualifiedIn = schemaIn.isEmpty() ? Sql.quote(relIn) : Sql.quote(schemaIn, relIn);
    List<String[]> found =
        Sql.query(
            c,
            """
            SELECT n.nspname, c.relname, c.relkind::text
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.oid = to_regclass(?)""",
            rs -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
            qualifiedIn);
    String dbName = spec.db().name();
    if (found.isEmpty()) {
      return new Validation(
          List.of(
              "does not exist in database instance '"
                  + dbName
                  + "'; create it in a migration, expected: "
                  + spec.expectedDdl()),
          Optional.empty());
    }
    String schema = found.get(0)[0];
    String rel = found.get(0)[1];
    String relkind = found.get(0)[2];
    Optional<String[]> names = Optional.of(new String[] {schema, rel});
    String ident = Sql.quote(schema, rel);
    if (!relkind.equals("p")) {
      return new Validation(
          List.of(
              "is not a partitioned table (relkind \""
                  + relkind
                  + "\"); partition it from the start, expected: "
                  + spec.expectedDdl()
                  + ". Converting an existing table is not supported yet"),
          names);
    }

    List<Shape> shapes =
        Sql.query(
            c,
            """
            SELECT pt.partstrat::text, pt.partnatts::int, pt.partattrs[0]::int,
                   coalesce(a.attname::text, ''),
                   coalesce(a.atttypid = 'timestamptz'::regtype, false),
                   coalesce(a.atttypid IN ('text'::regtype, 'varchar'::regtype), false),
                   coalesce(a.atttypid = 'date'::regtype, false),
                   coalesce(co.collname::text, ''),
                   coalesce(cc.collname::text, ''),
                   pg_get_partkeydef(pt.partrelid)
            FROM pg_partitioned_table pt
            LEFT JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
            LEFT JOIN pg_collation co ON co.oid = pt.partcollation[0]
            LEFT JOIN pg_collation cc ON cc.oid = a.attcollation
            WHERE pt.partrelid = ?::regclass""",
            rs ->
                new Shape(
                    rs.getString(1),
                    rs.getInt(2),
                    rs.getInt(3),
                    rs.getString(4),
                    rs.getBoolean(5),
                    rs.getBoolean(6),
                    rs.getBoolean(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getString(10)),
            ident);
    if (shapes.isEmpty()) {
      return new Validation(List.of("cannot read its partition key"), names);
    }
    Shape s = shapes.get(0);
    String column = spec.key().column();
    String ddl = spec.expectedDdl();
    if (!s.kind().equals("r") || s.natts() != 1 || s.attnum() == 0 || !s.attname().equals(column)) {
      return new Validation(
          List.of(
              "is partitioned by "
                  + s.keydef()
                  + ", expected PARTITION BY RANGE ("
                  + column
                  + "); expected: "
                  + ddl),
          names);
    }
    List<String> out = new ArrayList<>();
    boolean keyInC = s.collation().equals("C") || s.collation().equals("POSIX");
    boolean columnInC = s.columnCollation().equals("C") || s.columnCollation().equals("POSIX");
    switch (spec.key().kind()) {
      case ID -> {
        if (!s.text()) {
          out.add("key column \"" + column + "\" must be text or varchar to hold ids, expected: " + ddl);
        }
        if (!keyInC) {
          out.add(
              "key column \""
                  + column
                  + "\" has collation \""
                  + s.collation()
                  + "\", but range bounds on ids compare in byte order: declare it COLLATE \"C\" "
                  + "(the column definition `"
                  + column
                  + " text COLLATE \"C\" PRIMARY KEY`); expected: "
                  + ddl);
        } else if (!columnInC) {
          out.add(
              "the partition key is in collation \"C\" but the column \""
                  + column
                  + "\" has collation \""
                  + s.columnCollation()
                  + "\", and PostgreSQL does not prune `WHERE "
                  + column
                  + " = $1` unless the column itself is in the key's collation: declare the column `"
                  + column
                  + " text COLLATE \"C\"`; expected: "
                  + ddl);
        }
      }
      case COLUMN -> {
        if (spec.key().isDate()) {
          if (s.timestamptz()) {
            out.add(
                "key column \""
                    + column
                    + "\" is a timestamptz column: declare the table with Key.byColumn (byDateColumn is for date); expected: "
                    + ddl);
          } else if (!s.date()) {
            out.add(
                "key column \""
                    + column
                    + "\" must be date (a timestamp without time zone is refused too), expected: "
                    + ddl);
          }
        } else if (s.date()) {
          out.add(
              "key column \""
                  + column
                  + "\" is a date column: declare the table with Key.byDateColumn (byColumn is for timestamptz); expected: "
                  + ddl);
        } else if (!s.timestamptz()) {
          out.add("key column \"" + column + "\" must be timestamptz, expected: " + ddl);
        }
      }
    }
    int defaults =
        Sql.queryOne(
            c,
            """
            SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
            WHERE i.inhparent = ?::regclass AND pg_get_expr(c.relpartbound, c.oid) = 'DEFAULT'""",
            rs -> rs.getInt(1),
            ident);
    if (defaults > 0) {
      out.add(
          "has a DEFAULT partition, which blocks creating later partitions whose rows it already holds; "
              + "detach and drop it (this framework fails loudly instead of collecting stray rows)");
    }
    if (spec.table().archives()) {
      boolean ok =
          Sql.queryOne(c, "SELECT to_regclass('fw_partition_archives') IS NOT NULL", rs -> rs.getBoolean(1));
      if (!ok) {
        out.add(
            "archives to an object store, so table fw_partition_archives must exist in database instance '"
                + dbName
                + "': list PartitionsModule, whose migration creates it on the default instance "
                + "(migrations run on the default instance only; create the table by hand elsewhere)");
      }
    }
    return new Validation(out, names);
  }
}
