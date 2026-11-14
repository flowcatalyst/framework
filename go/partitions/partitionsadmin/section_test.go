package partitionsadmin_test

import (
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/partitions"
)

func (a answer) list(key string) []map[string]any {
	out := []map[string]any{}
	items, _ := a.tree()[key].([]any)
	for _, i := range items {
		if m, ok := i.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func TestTheListAnswersEveryTableWithoutItsPartitions(t *testing.T) {
	t.Parallel()
	r := startRig(t)
	a := r.send("GET", "/api/admin/partitions", "", operatorViewOnly)
	if a.status != 200 {
		t.Fatalf("%d %s", a.status, a.body)
	}
	tables := a.list("tables")
	if len(tables) != 2 || tables[0]["name"] != "public.readings" || tables[1]["name"] != "public.samples" {
		t.Fatalf("tables %s", a.body)
	}
	readings := tables[0]
	for _, f := range []string{"partitions", "detached", "strays"} {
		if _, has := readings[f]; has {
			t.Errorf("the list carries %s", f)
		}
	}
	// the earliest removal: November ends 2026-12-01, plus the 90 days of the retention
	want := map[string]any{
		"key": `ByID("rdg") on id`, "interval": "MONTH", "ahead": float64(2), "healthy": true, "partitionCount": float64(3),
		"oldestFrom": "2026-11-01T00:00:00Z", "nextRemovalDue": "2027-03-01T00:00:00Z",
	}
	for k, v := range want {
		if readings[k] != v {
			t.Errorf("readings.%s = %v, want %v", k, readings[k], v)
		}
	}
	ret, _ := readings["retention"].(map[string]any)
	if ret["kind"] != "ARCHIVE_THEN_DROP" || ret["after"] != "PT2160H" || ret["store"] != "archive" {
		t.Errorf("retention %v (90 days is PT2160H)", ret)
	}
	if _, has := readings["lastPass"]; has {
		t.Error("a last pass before any pass")
	}
	if _, has := readings["database"]; has {
		t.Error("database on the default instance")
	}
	samplesRet, _ := tables[1]["retention"].(map[string]any)
	if samplesRet["kind"] != "DROP" || samplesRet["after"] != "PT720H" || samplesRet["store"] != nil {
		t.Errorf("samples retention %v", samplesRet)
	}
	if tables[1]["interval"] != "WEEK" || tables[1]["key"] != `ByColumn("recorded_at")` {
		t.Errorf("samples %v", tables[1])
	}
}

func TestOneTableWithItsPartitionsAndTheRefusalOfAnUnknownOne(t *testing.T) {
	t.Parallel()
	r := startRig(t)
	for _, name := range []string{"readings", "public.readings"} {
		a := r.send("GET", "/api/admin/partitions/"+name, "", operatorViewOnly)
		if a.status != 200 {
			t.Fatalf("%s: %d %s", name, a.status, a.body)
		}
		tree := a.tree()
		parts := a.list("partitions")
		if tree["name"] != "public.readings" || len(parts) != 3 || parts[0]["name"] != "readings_p20261101" || parts[0]["position"] != "CURRENT" || parts[1]["position"] != "FUTURE" {
			t.Fatalf("%s: %s", name, a.body)
		}
		p := parts[0]
		if p["from"] != "2026-11-01T00:00:00Z" || p["to"] != "2026-12-01T00:00:00Z" || p["bytes"].(float64) <= 0 { //nolint:forcetypeassert // the test's own answer
			t.Errorf("partition %v", p)
		}
		if _, has := p["rows"]; has {
			t.Errorf("an estimate before ANALYZE: %v", p["rows"])
		}
		if p["removalDue"] != "2027-03-01T00:00:00Z" {
			t.Errorf("removalDue %v, want 2026-12-01 plus 90 days", p["removalDue"])
		}
		if d, _ := tree["detached"].([]any); d == nil || len(d) != 0 {
			t.Errorf("detached %v, want []", tree["detached"])
		}
		if s, _ := tree["strays"].([]any); s == nil || len(s) != 0 {
			t.Errorf("strays %v, want []", tree["strays"])
		}
	}
	for _, target := range []string{"/api/admin/partitions/nope", "/api/admin/partitions/nope/archives"} {
		a := r.send("GET", target, "", operatorViewOnly)
		if a.status != 404 || a.errorCode() != "PARTITION_TABLE_NOT_FOUND" {
			t.Errorf("%s: %d %s", target, a.status, a.body)
		}
	}
}

func idAt(at time.Time) string {
	return fw.NewIds(fwtest.NewClock(at)).Prefixed(fw.MustIDPrefix("rdg"))
}

func TestAPassArchivesAndTheArchivesPageNewestFirst(t *testing.T) {
	t.Parallel()
	r := startRig(t)
	if _, err := r.ps.Ensure(t.Context(), "readings", time.Date(2026, 4, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 5, 31, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	for i := range 4 {
		r.exec("INSERT INTO readings (id, payload) VALUES ($1, 'x')", idAt(time.Date(2026, 4, 3+i, 1, 0, 0, 0, time.UTC)))
	}
	r.exec("INSERT INTO readings (id, payload) VALUES ($1, 'x')", idAt(time.Date(2026, 5, 3, 1, 0, 0, 0, time.UTC)))

	a := r.send("POST", "/api/admin/partitions/passes", `{"table":"public.readings"}`, operatorWithBoth)
	if a.status != 200 {
		t.Fatalf("%d %s", a.status, a.body)
	}
	results := a.list("results")
	if len(results) != 1 || results[0]["table"] != "public.readings" || results[0]["archived"] != float64(2) || results[0]["dropped"] != float64(2) || results[0]["created"] != float64(0) {
		t.Fatalf("results %s", a.body)
	}
	if failed, ok := results[0]["failed"].([]any); !ok || len(failed) != 0 {
		t.Errorf("failed %v, want []", results[0]["failed"])
	}
	// every table when the body names none
	a = r.send("POST", "/api/admin/partitions/passes", `{}`, operatorWithBoth)
	if got := a.list("results"); len(got) != 2 || got[0]["table"] != "public.readings" || got[1]["table"] != "public.samples" {
		t.Fatalf("all tables: %s", a.body)
	}

	a = r.send("GET", "/api/admin/partitions/readings/archives", "", operatorViewOnly)
	items := a.list("items")
	if a.status != 200 || len(items) != 2 || items[0]["partition"] != "readings_p20260501" || items[1]["partition"] != "readings_p20260401" {
		t.Fatalf("archives %d %s", a.status, a.body)
	}
	if _, has := a.tree()["nextCursor"]; has {
		t.Error("a next cursor on the only page")
	}
	may := items[0]
	if may["table"] != "public.readings" || may["rows"] != float64(1) || may["objects"] != float64(1) || may["from"] != "2026-05-01T00:00:00Z" ||
		may["to"] != "2026-06-01T00:00:00Z" || may["key"] != partitions.ArchiveKey("public", "readings", "readings_p20260501") || may["bytes"].(float64) <= 0 { //nolint:forcetypeassert // the test's own answer
		t.Errorf("record %v", may)
	}
	if items[1]["rows"] != float64(4) {
		t.Errorf("april %v", items[1])
	}
	// paging: limit and after, in the manner of admin.Page
	a = r.send("GET", "/api/admin/partitions/readings/archives?limit=1", "", operatorViewOnly)
	page1 := a.list("items")
	if len(page1) != 1 || page1[0]["partition"] != "readings_p20260501" || a.tree()["nextCursor"] != "readings_p20260501" {
		t.Fatalf("page 1: %s", a.body)
	}
	a = r.send("GET", "/api/admin/partitions/readings/archives?limit=1&after=readings_p20260501", "", operatorViewOnly)
	page2 := a.list("items")
	if len(page2) != 1 || page2[0]["partition"] != "readings_p20260401" {
		t.Fatalf("page 2: %s", a.body)
	}
	if _, has := a.tree()["nextCursor"]; has {
		t.Errorf("a next cursor on the last page: %s", a.body)
	}
	a = r.send("GET", "/api/admin/partitions/readings/archives?after=readings_p20260401", "", operatorViewOnly)
	if a.status != 200 || !strings.Contains(a.body, `"items":[]`) {
		t.Errorf("after the last: %s", a.body)
	}
	// the last pass is on the list now
	a = r.send("GET", "/api/admin/partitions", "", operatorViewOnly)
	lp, _ := a.list("tables")[0]["lastPass"].(map[string]any)
	if lp["ensure"] != "OK" || lp["retention"] != "OK" || lp["archive"] != "OK" || lp["endedAt"] != "2026-11-14T10:00:00Z" {
		t.Errorf("last pass %v", lp)
	}
	samplesLP, _ := a.list("tables")[1]["lastPass"].(map[string]any)
	if samplesLP["archive"] != "SKIPPED" {
		t.Errorf("samples last pass %v: the archive step does not apply to DropAfter", samplesLP)
	}
	logs := r.logs.String()
	for _, want := range []string{"partitions: manual pass", "principal=prn_TESTOP", "tier=ANCHOR", "table=public.readings", "table=all"} {
		if !strings.Contains(logs, want) {
			t.Errorf("the record lacks %q:\n%s", want, logs)
		}
	}
}

func TestAPassIsRefusedWhileAnotherRunsAndAnUnknownTableIs404(t *testing.T) {
	t.Parallel()
	r := startRig(t)
	a := r.send("POST", "/api/admin/partitions/passes", `{"table":"nope"}`, operatorWithBoth)
	if a.status != 404 || a.errorCode() != "PARTITION_TABLE_NOT_FOUND" {
		t.Errorf("%d %s", a.status, a.body)
	}
	holder := locks.New(r.lockStore, locks.WithClock(r.clock))
	key, err := locks.ServiceKey(partitions.LockName)
	if err != nil {
		t.Fatal(err)
	}
	acq, err := holder.Acquire(t.Context(), key, time.Hour, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	held, ok := acq.(locks.Acquired)
	if !ok {
		t.Fatalf("could not take the lock: %T", acq)
	}
	a = r.send("POST", "/api/admin/partitions/passes", `{}`, operatorWithBoth)
	if a.status != 409 || a.errorCode() != "PARTITION_PASS_RUNNING" {
		t.Errorf("a pass while another runs: %d %s", a.status, a.body)
	}
	if _, err := holder.Release(t.Context(), held.Lease); err != nil {
		t.Fatal(err)
	}
	if a := r.send("POST", "/api/admin/partitions/passes", `{}`, operatorWithBoth); a.status != 200 {
		t.Errorf("after it ended: %d %s", a.status, a.body)
	}
	if a := r.send("POST", "/api/admin/partitions/passes", `{"table":`+strings.Repeat("1", 5)+`}`, operatorWithBoth); a.status != 400 {
		t.Errorf("a malformed body: %d", a.status)
	}
}

func TestEnsureAnswersCreatedAndAlreadyDueAndEveryRefusalHasItsCode(t *testing.T) {
	t.Parallel()
	r := startRig(t)
	ensure := func(table, body string) answer {
		return r.send("POST", "/api/admin/partitions/"+table+"/ensure", body, operatorWithBoth)
	}
	// 2026-08-16 is the cutoff: May to July end before it, so they are past retention at once
	a := ensure("readings", `{"from":"2026-05-10T00:00:00Z","to":"2026-12-31T00:00:00Z"}`)
	if a.status != 200 || a.tree()["created"] != float64(6) || a.tree()["alreadyDue"] != float64(3) {
		t.Fatalf("%d %s", a.status, a.body)
	}
	if a := ensure("public.readings", `{"from":"2026-05-10T00:00:00Z","to":"2026-12-31T00:00:00Z"}`); a.tree()["created"] != float64(0) {
		t.Errorf("a second call: %s", a.body)
	}
	for _, c := range []struct {
		name, table, body string
		status            int
		code              string
	}{
		{"unknown table", "nope", `{"from":"2026-05-10T00:00:00Z","to":"2026-06-01T00:00:00Z"}`, 404, "PARTITION_TABLE_NOT_FOUND"},
		{"to before from", "readings", `{"from":"2026-06-10T00:00:00Z","to":"2026-06-01T00:00:00Z"}`, 400, "PARTITION_RANGE_INVALID"},
		{"before 1970", "readings", `{"from":"1969-06-10T00:00:00Z","to":"2026-06-01T00:00:00Z"}`, 400, "PARTITION_RANGE_INVALID"},
		{"too large", "readings", `{"from":"1990-01-01T00:00:00Z","to":"2026-01-01T00:00:00Z"}`, 400, "PARTITION_RANGE_TOO_LARGE"},
		{"a missing bound", "readings", `{"from":"2026-06-10T00:00:00Z"}`, 400, "VALIDATION"},
		{"not an instant", "readings", `{"from":"yesterday","to":"2026-06-01T00:00:00Z"}`, 400, ""},
	} {
		a := ensure(c.table, c.body)
		if a.status != c.status || (c.code != "" && a.errorCode() != c.code) {
			t.Errorf("%s: %d %s, want %d %s", c.name, a.status, a.body, c.status, c.code)
		}
	}
	// a table of a partition's name that is not attached
	r.exec("CREATE TABLE readings_p20250101 (id text)")
	a = ensure("readings", `{"from":"2025-01-05T00:00:00Z","to":"2025-01-20T00:00:00Z"}`)
	if a.status != 409 || a.errorCode() != "PARTITION_DETACHED_LEFTOVER" || !strings.Contains(a.errorMessage(), "readings_p20250101") || strings.HasPrefix(a.errorMessage(), "partitions:") {
		t.Errorf("a detached leftover: %d %s", a.status, a.body)
	}
	if !strings.Contains(r.logs.String(), "partitions: manual ensure") {
		t.Errorf("no record of the manual ensure:\n%s", r.logs.String())
	}
	if !strings.Contains(r.logs.String(), "principal=prn_TESTOP") {
		t.Errorf("the record does not name the principal:\n%s", r.logs.String())
	}
}
