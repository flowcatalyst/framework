package partitions

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/objectstore"
)

// archiveContentType is the content type the archive objects are stored with.
const archiveContentType = "application/gzip"

type archivePart struct {
	key    string
	rows   int64
	bytes  int64
	sha256 string
}

// ArchiveKey returns the object key of the first (and, for a partition that fits, only) object of
// an archived partition: "partitions/<schema>.<table>/<partition name>.ndjson.gz".
func ArchiveKey(schema, table, partition string) string {
	return "partitions/" + schema + "." + table + "/" + partition + ".ndjson.gz"
}

// archivePartKey is the key of object number part (1-based): part 1 is [ArchiveKey], part k > 1
// is "<partition name>.part<k>.ndjson.gz" beside it.
func archivePartKey(schema, table, partition string, part int) string {
	if part == 1 {
		return ArchiveKey(schema, table, partition)
	}
	return fmt.Sprintf("partitions/%s.%s/%s.part%d.ndjson.gz", schema, table, partition, part)
}

// archive streams the partition's rows as gzip'd NDJSON (row_to_json, one object per line) into
// the table's object store, cut into objects of about segmentBytes compressed bytes each (a store
// takes whole values), verifies every object by reading it back, and records the manifest rows.
// It returns the number of rows archived. Memory use is one segment, not the partition.
func (p *Partitions) archive(ctx context.Context, tg *target, name string) (int64, error) {
	tx, err := tg.db.Pool().BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly})
	if err != nil {
		return 0, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	rows, err := tx.Query(ctx, "SELECT row_to_json(t)::text FROM "+tg.childIdent(name)+" t")
	if err != nil {
		return 0, err
	}
	defer rows.Close()

	var (
		buf   bytes.Buffer
		gz    = gzip.NewWriter(&buf)
		parts []archivePart
		inSeg int64
		total int64
	)
	flush := func() error {
		if err := gz.Close(); err != nil {
			return err
		}
		content := bytes.Clone(buf.Bytes())
		key := archivePartKey(tg.schema, tg.rel, name, len(parts)+1)
		sum := sha256.Sum256(content)
		part := archivePart{key: key, rows: inSeg, bytes: int64(len(content)), sha256: hex.EncodeToString(sum[:])}
		if err := putVerified(ctx, tg.store, part, content); err != nil {
			return err
		}
		parts = append(parts, part)
		buf.Reset()
		gz.Reset(&buf)
		inSeg = 0
		return nil
	}
	for rows.Next() {
		var line string
		if err := rows.Scan(&line); err != nil {
			return 0, err
		}
		if _, err := gz.Write([]byte(line + "\n")); err != nil {
			return 0, err
		}
		inSeg++
		total++
		if buf.Len() >= p.segment {
			if err := flush(); err != nil {
				return 0, err
			}
		}
	}
	if err := rows.Err(); err != nil {
		return 0, err
	}
	if inSeg > 0 || len(parts) == 0 { // an empty partition is one empty object, so "archived" is on record
		if err := flush(); err != nil {
			return 0, err
		}
	}
	rows.Close()

	stale, err := recordManifest(ctx, tg, name, parts, p.clock.Now())
	if err != nil {
		return 0, fmt.Errorf("record manifest: %w", err)
	}
	for _, key := range stale { // objects of an earlier, longer archive of the same partition
		if err := tg.store.Delete(ctx, key); err != nil {
			p.log.Warn("could not delete a stale archive object", slog.String("table", tg.qualified), slog.String("key", key))
		}
	}
	tg.archived.Inc()
	tg.rows.Add(float64(total))
	p.log.Info("partition archived", slog.String("table", tg.qualified), slog.String("partition", name),
		slog.Int64("rows", total), slog.Int("objects", len(parts)))
	return total, nil
}

// putVerified stores the object and reads it back: it must exist, with the recorded size and
// digest.
func putVerified(ctx context.Context, store objectstore.Store, part archivePart, content []byte) error {
	if err := store.PutObject(ctx, part.key, objectstore.Object{Content: content, ContentType: archiveContentType}); err != nil {
		return fmt.Errorf("put %s: %w", part.key, err)
	}
	got, found, err := store.GetObject(ctx, part.key)
	if err != nil {
		return fmt.Errorf("verify %s: %w", part.key, err)
	}
	if !found {
		return fmt.Errorf("verify %s: the object is not there after the put", part.key)
	}
	if int64(len(got.Content)) != part.bytes {
		return fmt.Errorf("verify %s: stored %d bytes, wrote %d", part.key, len(got.Content), part.bytes)
	}
	if sum := sha256.Sum256(got.Content); hex.EncodeToString(sum[:]) != part.sha256 {
		return fmt.Errorf("verify %s: the stored object differs from what was written", part.key)
	}
	return nil
}

// recordManifest replaces the manifest rows of the partition with the new objects and returns the
// keys of earlier objects that the new archive no longer uses.
func recordManifest(ctx context.Context, tg *target, name string, parts []archivePart, at time.Time) ([]string, error) {
	var stale []string
	err := pgx.BeginFunc(ctx, tg.db.Pool(), func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, "SELECT object_key FROM fw_partition_archives WHERE table_name = $1 AND partition_name = $2", tg.qualified, name)
		if err != nil {
			return err
		}
		var old []string
		for rows.Next() {
			var k string
			if err := rows.Scan(&k); err != nil {
				rows.Close()
				return err
			}
			old = append(old, k)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "DELETE FROM fw_partition_archives WHERE table_name = $1 AND partition_name = $2", tg.qualified, name); err != nil {
			return err
		}
		used := map[string]bool{}
		for i, part := range parts {
			used[part.key] = true
			if _, err := tx.Exec(ctx, `INSERT INTO fw_partition_archives
(table_name, partition_name, part, row_count, byte_count, sha256, object_key, archived_at)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`, tg.qualified, name, i+1, part.rows, part.bytes, part.sha256, part.key, at); err != nil {
				return err
			}
		}
		for _, k := range old {
			if !used[k] {
				stale = append(stale, k)
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return stale, nil
}
