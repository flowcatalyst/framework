package io.flowcatalyst.framework.partitions;

import io.flowcatalyst.framework.objectstore.ObjectStore;
import io.flowcatalyst.framework.objectstore.StoredObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/** The archive: a detached partition written to an object store as gzip'd NDJSON, verified, and recorded (contract section 5). */
final class Archiver {

  /** The content type the archive objects are stored with. */
  static final String CONTENT_TYPE = "application/gzip";

  private static final int FETCH_SIZE = 1000;

  private Archiver() {}

  private record Part(String key, long rows, long bytes, String sha256) {}

  /**
   * The key of the first (and, for a partition that fits, only) object of an archived partition:
   * {@code partitions/<schema>.<table>/<partition name>.ndjson.gz}.
   */
  static String archiveKey(String schema, String table, String partition) {
    return "partitions/" + schema + "." + table + "/" + partition + ".ndjson.gz";
  }

  /** The key of object number {@code part} (1-based): part 1 is {@link #archiveKey}, part k > 1 {@code .part<k>.ndjson.gz}. */
  static String partKey(String schema, String table, String partition, int part) {
    return part == 1
        ? archiveKey(schema, table, partition)
        : "partitions/" + schema + "." + table + "/" + partition + ".part" + part + ".ndjson.gz";
  }

  /**
   * Streams the partition's rows as gzip'd NDJSON ({@code row_to_json}, one object per line) into the table's
   * object store, cut into objects of about {@code segmentBytes} compressed bytes each (a store takes whole
   * values), verifies every object by reading it back, and records the manifest rows. Memory use is one
   * segment, not the partition.
   *
   * @return the number of rows archived
   */
  static long archive(Core core, Target tg, String name) throws SQLException, IOException {
    ObjectStore store = tg.store();
    List<Part> parts = new ArrayList<>();
    long total = 0;
    try (Connection c = tg.db().dataSource().getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try (PreparedStatement ps = c.prepareStatement(Sql.archiveSelect(tg.childIdent(name)))) {
        ps.setFetchSize(FETCH_SIZE);
        try (ResultSet rs = ps.executeQuery()) {
          Segment segment = new Segment(core.segmentBytes);
          try {
            while (rs.next()) {
              segment.write(rs.getString(1) + "\n");
              total++;
              if (segment.size() >= core.segmentBytes) {
                parts.add(flush(tg, store, name, parts.size() + 1, segment));
                segment = new Segment(core.segmentBytes);
              }
            }
            // an empty partition is one empty object, so that "archived" is on record
            if (segment.rows > 0 || parts.isEmpty()) {
              parts.add(flush(tg, store, name, parts.size() + 1, segment));
            }
          } finally {
            segment.close();
          }
        }
      } finally {
        c.rollback(); // read-only: nothing to keep
      }
    }
    List<String> stale = recordManifest(tg, name, parts, core.clock.instant());
    for (String key : stale) { // objects of an earlier, longer archive of the same partition
      try {
        store.delete(key);
      } catch (RuntimeException e) {
        Log.warn("could not delete a stale archive object", "table", tg.qualified, "key", key);
      }
    }
    tg.archived.inc();
    tg.rows.inc((double) total);
    Log.info(
        "partition archived",
        "table",
        tg.qualified,
        "partition",
        name,
        "rows",
        total,
        "objects",
        parts.size());
    return total;
  }

  /** One gzip stream: a complete object of the archive. */
  private static final class Segment implements AutoCloseable {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final GZIPOutputStream gz;
    private final int flushAt;
    private int sinceFlush;
    long rows;

    /** @param segmentBytes the size the compressed segment is cut at; the compressor is flushed often enough to see it */
    Segment(int segmentBytes) throws IOException {
      this.gz = new GZIPOutputStream(buf, 8192, true);
      this.flushAt = Math.min(65_536, Math.max(512, segmentBytes));
    }

    void write(String line) throws IOException {
      byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
      gz.write(bytes);
      rows++;
      sinceFlush += bytes.length;
      if (sinceFlush >= flushAt) {
        // a compressor holds what it was given until a block is full: flush, so size() is what a store would get
        gz.flush();
        sinceFlush = 0;
      }
    }

    /** The compressed bytes written to the buffer so far. */
    int size() {
      return buf.size();
    }

    byte[] finish() throws IOException {
      gz.close();
      return buf.toByteArray();
    }

    @Override
    public void close() throws IOException {
      gz.close();
    }
  }

  private static Part flush(Target tg, ObjectStore store, String name, int number, Segment segment)
      throws IOException {
    byte[] content = segment.finish();
    Part part =
        new Part(partKey(tg.schema, tg.rel, name, number), segment.rows, content.length, sha256(content));
    putVerified(store, part, content);
    return part;
  }

  /** Stores the object and reads it back: it must exist, with the recorded size and digest. */
  private static void putVerified(ObjectStore store, Part part, byte[] content) throws IOException {
    try {
      store.put(part.key(), content, CONTENT_TYPE);
    } catch (RuntimeException e) {
      throw new IOException("put " + part.key() + ": " + Log.describe(e), e);
    }
    Optional<StoredObject> got;
    try {
      got = store.read(part.key());
    } catch (RuntimeException e) {
      throw new IOException("verify " + part.key() + ": " + Log.describe(e), e);
    }
    if (got.isEmpty()) {
      throw new IOException("verify " + part.key() + ": the object is not there after the put");
    }
    byte[] stored = got.get().content();
    if (stored.length != part.bytes()) {
      throw new IOException(
          "verify " + part.key() + ": stored " + stored.length + " bytes, wrote " + part.bytes());
    }
    if (!sha256(stored).equals(part.sha256())) {
      throw new IOException("verify " + part.key() + ": the stored object differs from what was written");
    }
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }

  /**
   * Replaces the manifest rows of the partition with the new objects and returns the keys of earlier
   * objects the new archive no longer uses.
   */
  private static List<String> recordManifest(Target tg, String name, List<Part> parts, Instant at)
      throws SQLException {
    try (Connection c = tg.db().dataSource().getConnection()) {
      c.setAutoCommit(false);
      try {
        List<String> old =
            Sql.query(
                c,
                "SELECT object_key FROM fw_partition_archives WHERE table_name = ? AND partition_name = ?",
                rs -> rs.getString(1),
                tg.qualified,
                name);
        Sql.update(
            c,
            "DELETE FROM fw_partition_archives WHERE table_name = ? AND partition_name = ?",
            tg.qualified,
            name);
        Set<String> used = new HashSet<>();
        for (int i = 0; i < parts.size(); i++) {
          Part part = parts.get(i);
          used.add(part.key());
          Sql.update(
              c,
              """
              INSERT INTO fw_partition_archives
              (table_name, partition_name, part, row_count, byte_count, sha256, object_key, archived_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
              tg.qualified,
              name,
              i + 1,
              part.rows(),
              part.bytes(),
              part.sha256(),
              part.key(),
              OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
        }
        c.commit();
        List<String> stale = new ArrayList<>();
        for (String k : old) {
          if (!used.contains(k)) {
            stale.add(k);
          }
        }
        return stale;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw new SQLException("record manifest: " + Log.describe(e), e);
      }
    }
  }
}
