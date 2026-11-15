package io.flowcatalyst.framework.partitions;

import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.objectstore.ObjectStore;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** A declaration that passed {@link Table#check()}, with its database and store resolved: nothing is null any more. */
record Spec(
    Table table,
    Key key,
    Interval interval,
    int ahead,
    Retention retention,
    Database db,
    @Nullable ObjectStore store) {

  /** What the table's retention removes after, zero for keepForever. */
  Duration after() {
    return switch (retention) {
      case Retention.Drop d -> d.after();
      case Retention.ArchiveThenDrop a -> a.after();
      case Retention.Keep k -> Duration.ZERO;
    };
  }

  String declaredName() {
    return table.name();
  }

  /** The shape of table the declaration needs, for the start-up messages. */
  String expectedDdl() {
    String n = table.name();
    String c = key.column();
    return switch (key.kind()) {
      case ID -> "CREATE TABLE " + n + " (" + c + " text COLLATE \"C\" PRIMARY KEY, ...) PARTITION BY RANGE (" + c + ")";
      case COLUMN ->
          "CREATE TABLE "
              + n
              + " (id ..., "
              + c
              + (key.isDate() ? " date" : " timestamptz")
              + " NOT NULL, ..., PRIMARY KEY (id, "
              + c
              + ")) PARTITION BY RANGE ("
              + c
              + ")";
    };
  }
}
