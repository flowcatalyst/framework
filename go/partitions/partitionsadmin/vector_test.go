package partitionsadmin

import (
	"encoding/json/jsontext"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/internal/javafmt"
	"github.com/flowcatalyst/framework/go/partitions"
)

const vectorFile = "admin/partitions.json"

type vectorInput struct {
	Name      string  `json:"name"`
	Database  *string `json:"database"`
	Key       string  `json:"key"`
	Interval  string  `json:"interval"`
	Ahead     int     `json:"ahead"`
	Retention struct {
		Kind         string `json:"kind"`
		AfterSeconds int64  `json:"afterSeconds"`
		Store        string `json:"store"`
	} `json:"retention"`
	Healthy  bool `json:"healthy"`
	LastPass *struct {
		EndedAt   time.Time `json:"endedAt"`
		Ensure    string    `json:"ensure"`
		Retention string    `json:"retention"`
		Archive   string    `json:"archive"`
		Created   int       `json:"created"`
		Dropped   int       `json:"dropped"`
		Archived  int       `json:"archived"`
	} `json:"lastPass"`
	Partitions []struct {
		Name       string     `json:"name"`
		From       time.Time  `json:"from"`
		To         time.Time  `json:"to"`
		Position   string     `json:"position"`
		Bytes      int64      `json:"bytes"`
		Rows       *int64     `json:"rows"`
		RemovalDue *time.Time `json:"removalDue"`
		Archived   *struct {
			Objects    int       `json:"objects"`
			Rows       int64     `json:"rows"`
			Bytes      int64     `json:"bytes"`
			ArchivedAt time.Time `json:"archivedAt"`
		} `json:"archived"`
	} `json:"partitions"`
	Detached []string `json:"detached"`
	Strays   []string `json:"strays"`
	Bytes    int64    `json:"bytes"`
	Rows     int64    `json:"rows"`
}

func (in vectorInput) status(t *testing.T) partitions.TableStatus {
	t.Helper()
	interval := map[string]partitions.Interval{"DAY": partitions.Day, "WEEK": partitions.Week, "MONTH": partitions.Month, "YEAR": partitions.Year}[in.Interval]
	st := partitions.TableStatus{
		Name: in.Name, Key: in.Key, Interval: interval, Ahead: in.Ahead, Healthy: in.Healthy,
		Retention: partitions.RetentionStatus{Kind: partitions.RetentionKind(in.Retention.Kind), After: time.Duration(in.Retention.AfterSeconds) * time.Second, Store: in.Retention.Store},
		Detached:  in.Detached, Strays: in.Strays, Bytes: in.Bytes, Rows: in.Rows,
	}
	if in.Database != nil {
		st.Database = *in.Database
	}
	if l := in.LastPass; l != nil {
		st.LastPass = &partitions.LastPass{
			EndedAt: l.EndedAt, Ensure: partitions.StepStatus(l.Ensure), Retention: partitions.StepStatus(l.Retention), Archive: partitions.StepStatus(l.Archive),
			Created: l.Created, Dropped: l.Dropped, Archived: l.Archived,
		}
	}
	for _, p := range in.Partitions {
		ps := partitions.PartitionStatus{Name: p.Name, From: p.From, To: p.To, Position: partitions.Position(p.Position), Bytes: p.Bytes, Rows: p.Rows, RemovalDue: p.RemovalDue}
		if p.Archived != nil {
			ps.Archived = &partitions.ArchivedPartition{Objects: p.Archived.Objects, Rows: p.Archived.Rows, Bytes: p.Archived.Bytes, ArchivedAt: p.Archived.ArchivedAt}
		}
		st.Partitions = append(st.Partitions, ps)
	}
	return st
}

func TestProjectionVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Projection []struct {
			Name   string      `json:"name"`
			Input  vectorInput `json:"input"`
			Expect struct {
				Summary jsontext.Value `json:"summary"`
				Table   jsontext.Value `json:"table"`
			} `json:"expect"`
		} `json:"projection"`
	}
	conformance.ReadJSON(t, vectorFile, &doc)
	if len(doc.Projection) < 2 {
		t.Fatalf("%s: %d projection cases", vectorFile, len(doc.Projection))
	}
	for _, c := range doc.Projection {
		st := c.Input.status(t)
		summary, err := javafmt.MarshalJSON(summaryOf(st))
		if err != nil {
			t.Fatal(err)
		}
		table, err := javafmt.MarshalJSON(tableOf(st))
		if err != nil {
			t.Fatal(err)
		}
		conformance.EqualJSON(t, conformance.Where(vectorFile, c.Name, "summary"), c.Expect.Summary, summary)
		conformance.EqualJSON(t, conformance.Where(vectorFile, c.Name, "table"), c.Expect.Table, table)
	}
}

func TestDurationsVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Durations []struct {
			Seconds float64 `json:"seconds"`
			ISO     string  `json:"iso"`
		} `json:"durations"`
	}
	conformance.ReadJSON(t, vectorFile, &doc)
	if len(doc.Durations) == 0 {
		t.Fatalf("%s: no durations", vectorFile)
	}
	for _, c := range doc.Durations {
		if got := durationOf(time.Duration(c.Seconds * float64(time.Second))); string(got) != c.ISO {
			t.Errorf("%v seconds: %q, want %q", c.Seconds, got, c.ISO)
		}
	}
}
