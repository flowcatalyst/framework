package partitionsadmin

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/openapi"
	"github.com/flowcatalyst/framework/go/httpfw/validate"
	"github.com/flowcatalyst/framework/go/partitions"
)

// SectionID is the id of the section; its path is /admin/partitions.
const SectionID = "partitions"

// View is the permission that opens the section and reads it: {app}:admin:partitions:view.
func View(application fw.ApplicationCode) string {
	return application.String() + ":admin:partitions:view"
}

// Manage is the permission of the two actions (run a pass, ensure a range): {app}:admin:partitions:manage.
func Manage(application fw.ApplicationCode) string {
	return application.String() + ":admin:partitions:manage"
}

// ---- the wire ----

// Duration is an ISO-8601 duration ("PT2160H"), the framework's wire form of a duration.
type Duration string

// JSONSchema implements openapi.SchemaProvider.
func (Duration) JSONSchema() openapi.Schema { return openapi.String().Format("duration") }

// durationOf is java.time.Duration.toString for a non-negative duration: hours, minutes, seconds.
func durationOf(d time.Duration) Duration {
	if d <= 0 {
		return "PT0S"
	}
	hours := int64(d / time.Hour)
	minutes := int64(d%time.Hour) / int64(time.Minute)
	secs := int64(d%time.Minute) / int64(time.Second)
	nanos := int64(d % time.Second)
	var sb strings.Builder
	sb.WriteString("PT")
	if hours != 0 {
		sb.WriteString(strconv.FormatInt(hours, 10) + "H")
	}
	if minutes != 0 {
		sb.WriteString(strconv.FormatInt(minutes, 10) + "M")
	}
	if secs != 0 || nanos != 0 {
		sb.WriteString(strconv.FormatInt(secs, 10))
		if nanos != 0 {
			sb.WriteString("." + strings.TrimRight(strconv.FormatInt(1_000_000_000+nanos, 10)[1:], "0"))
		}
		sb.WriteString("S")
	}
	return Duration(sb.String())
}

// Interval is the width of a partition.
type Interval string

// EnumValues implements openapi.EnumValuer.
func (Interval) EnumValues() []string { return []string{"DAY", "WEEK", "MONTH", "YEAR"} }

// RetentionKind is what retention does to a partition past its time.
type RetentionKind string

// EnumValues implements openapi.EnumValuer.
func (RetentionKind) EnumValues() []string { return []string{"DROP", "ARCHIVE_THEN_DROP", "KEEP"} }

// Position is where a partition is against the module's clock.
type Position string

// EnumValues implements openapi.EnumValuer.
func (Position) EnumValues() []string { return []string{"PAST", "CURRENT", "FUTURE"} }

// StepStatus is the outcome of one step of a pass.
type StepStatus string

// EnumValues implements openapi.EnumValuer.
func (StepStatus) EnumValues() []string { return []string{"OK", "FAILED", "SKIPPED"} }

// Step names a step of a pass.
type Step string

// EnumValues implements openapi.EnumValuer.
func (Step) EnumValues() []string { return []string{"ensure", "retention", "archive"} }

// Retention is a table's declared retention. After is absent for KEEP, Store unless it archives.
type Retention struct {
	Kind  RetentionKind `json:"kind"`
	After *Duration     `json:"after,omitzero"`
	Store *string       `json:"store,omitzero"`
}

// LastPass is the last pass of a table, by whichever replica ran it.
type LastPass struct {
	EndedAt   time.Time  `json:"endedAt"`
	Ensure    StepStatus `json:"ensure"`
	Retention StepStatus `json:"retention"`
	Archive   StepStatus `json:"archive"`
	Created   int32      `json:"created"`
	Dropped   int32      `json:"dropped"`
	Archived  int32      `json:"archived"`
}

// Archived is what the manifest holds for an attached partition that was archived.
type Archived struct {
	Objects    int32     `json:"objects"`
	Rows       int64     `json:"rows"`
	Bytes      int64     `json:"bytes"`
	ArchivedAt time.Time `json:"archivedAt"`
}

// Partition is one attached partition. Rows is the planner's estimate and absent until the partition
// was analysed; RemovalDue is absent for a table that keeps everything.
type Partition struct {
	Name       string     `json:"name"`
	From       time.Time  `json:"from"`
	To         time.Time  `json:"to"`
	Position   Position   `json:"position"`
	Bytes      int64      `json:"bytes"`
	Rows       *int64     `json:"rows,omitzero"`
	RemovalDue *time.Time `json:"removalDue,omitzero"`
	Archived   *Archived  `json:"archived,omitzero"`
}

// TableSummary is a table in the list: its declaration and totals, without its partitions.
type TableSummary struct {
	Name           string     `json:"name"`
	Database       *string    `json:"database,omitzero"`
	Key            string     `json:"key"`
	Interval       Interval   `json:"interval"`
	Ahead          int32      `json:"ahead"`
	Retention      Retention  `json:"retention"`
	Healthy        bool       `json:"healthy"`
	LastPass       *LastPass  `json:"lastPass,omitzero"`
	PartitionCount int32      `json:"partitionCount"`
	Bytes          int64      `json:"bytes"`
	Rows           int64      `json:"rows"`
	OldestFrom     *time.Time `json:"oldestFrom,omitzero"`
	NextRemovalDue *time.Time `json:"nextRemovalDue,omitzero"`
}

// TableList is every declared table, in declaration order (there is a handful: not paged).
type TableList struct {
	Tables []TableSummary `json:"tables"`
}

// Table is the status of one table: the declaration, the last pass, every partition (oldest
// first), the detached tables and the strays.
type Table struct {
	Name       string      `json:"name"`
	Database   *string     `json:"database,omitzero"`
	Key        string      `json:"key"`
	Interval   Interval    `json:"interval"`
	Ahead      int32       `json:"ahead"`
	Retention  Retention   `json:"retention"`
	Healthy    bool        `json:"healthy"`
	LastPass   *LastPass   `json:"lastPass,omitzero"`
	Partitions []Partition `json:"partitions"`
	Detached   []string    `json:"detached"`
	Strays     []string    `json:"strays"`
	Bytes      int64       `json:"bytes"`
	Rows       int64       `json:"rows"`
}

// Archive is one archived partition. Key is the first object's key in the object store (the others
// sit beside it); it is not a credential.
type Archive struct {
	Table      string     `json:"table"`
	Partition  string     `json:"partition"`
	From       *time.Time `json:"from,omitzero"`
	To         *time.Time `json:"to,omitzero"`
	Objects    int32      `json:"objects"`
	Rows       int64      `json:"rows"`
	Bytes      int64      `json:"bytes"`
	ArchivedAt time.Time  `json:"archivedAt"`
	Key        string     `json:"key"`
}

// PassBody names the table to run a pass for; {} runs every table.
type PassBody struct {
	Table string `json:"table,omitzero"`
}

// PassResult is what one table's pass did. Failed lists the steps that failed (the log has the cause).
type PassResult struct {
	Table    string `json:"table"`
	Created  int32  `json:"created"`
	Dropped  int32  `json:"dropped"`
	Archived int32  `json:"archived"`
	Failed   []Step `json:"failed"`
}

// PassResults is the results of a manual pass, in declaration order.
type PassResults struct {
	Results []PassResult `json:"results"`
}

// EnsureBody is the window to create partitions for (RFC 3339 instants, both inclusive).
type EnsureBody struct {
	From time.Time `json:"from"`
	To   time.Time `json:"to"`
}

// EnsureResult says how many partitions were created and how many of the range are already past
// retention, which the next pass removes.
type EnsureResult struct {
	Created    int32 `json:"created"`
	AlreadyDue int32 `json:"alreadyDue"`
}

var (
	passRules = validate.Of(
		validate.Field("table", func(b PassBody) string { return b.Table }, validate.MaxLength(200)))
	ensureRules = validate.Of(
		validate.Field("from", func(b EnsureBody) time.Time { return b.From }, validate.Custom("NotNull", "must be given", func(t time.Time) bool { return !t.IsZero() })),
		validate.Field("to", func(b EnsureBody) time.Time { return b.To }, validate.Custom("NotNull", "must be given", func(t time.Time) bool { return !t.IsZero() })))

	tablePath    = httpfw.Path[string]("table")
	limitQuery   = httpfw.Query[int32]("limit").Default(50)
	archiveAfter = httpfw.Query[string]("after").Optional()
)

// Admin is the declared partitions section; [Admin.Build] binds its handlers.
type Admin struct {
	listOp     httpfw.Operation[httpfw.NoBody, TableList]
	getOp      httpfw.Operation[httpfw.NoBody, Table]
	archivesOp httpfw.Operation[httpfw.NoBody, admin.Page[Archive]]
	passOp     httpfw.Operation[PassBody, PassResults]
	ensureOp   httpfw.Operation[EnsureBody, EnsureResult]
}

func permission(text string) fw.Permission {
	p, err := fw.ParsePermission(text)
	if err != nil {
		panic("partitionsadmin: " + err.Error()) // built from an application code, which cannot be malformed
	}
	return p
}

// Declare registers the section (id partitions, path /admin/partitions, operators only) with the
// admin registry and declares its five operations. Call it at declaration time, after
// [admin.Declare]; bind the handlers with [Admin.Build] once [partitions.Build] has run.
func Declare(adm *admin.Declared) *Admin {
	app := adm.Application()
	web := adm.Web()
	httpfw.NameType[Retention](web, "AdminPartitionRetention")
	httpfw.NameType[LastPass](web, "AdminPartitionLastPass")
	httpfw.NameType[Archived](web, "AdminPartitionArchived")
	httpfw.NameType[Partition](web, "AdminPartition")
	httpfw.NameType[TableSummary](web, "AdminPartitionTableSummary")
	httpfw.NameType[TableList](web, "AdminPartitionTableList")
	httpfw.NameType[Table](web, "AdminPartitionTable")
	httpfw.NameType[Archive](web, "AdminPartitionArchive")
	httpfw.NameType[admin.Page[Archive]](web, "PageAdminPartitionArchive")
	httpfw.NameType[PassBody](web, "AdminPartitionPassBody")
	httpfw.NameType[PassResult](web, "AdminPartitionPassResult")
	httpfw.NameType[PassResults](web, "AdminPartitionPassResults")
	httpfw.NameType[EnsureBody](web, "AdminPartitionEnsureBody")
	httpfw.NameType[EnsureResult](web, "AdminPartitionEnsureResult")
	view, manage := permission(View(app)), permission(Manage(app))
	a := &Admin{}
	a.listOp = httpfw.Get[TableList]("/api/admin/partitions").
		ID("listAdminPartitions").Summary("The partitioned tables: declaration, health, last pass and totals").Tag("admin").
		Permission(view).OperatorsOnly().Build()
	a.getOp = httpfw.Get[Table]("/api/admin/partitions/{table}").
		ID("getAdminPartitionTable").Summary("One partitioned table with its partitions").Tag("admin").
		Path(tablePath).Permission(view).OperatorsOnly().
		Error(http.StatusNotFound, "PARTITION_TABLE_NOT_FOUND", "no declared table has this name").Build()
	a.archivesOp = httpfw.Get[admin.Page[Archive]]("/api/admin/partitions/{table}/archives").
		ID("listAdminPartitionArchives").Summary("The archived partitions of a table, newest first").Tag("admin").
		Path(tablePath).Query(limitQuery, archiveAfter).Permission(view).OperatorsOnly().
		Error(http.StatusNotFound, "PARTITION_TABLE_NOT_FOUND", "no declared table has this name").Build()
	a.passOp = httpfw.Post[PassBody, PassResults]("/api/admin/partitions/passes", passRules).
		ID("runAdminPartitionPass").Summary("Run the ensure-then-retention pass now, for one table or all").Tag("admin").
		Permission(manage).OperatorsOnly().
		Error(http.StatusNotFound, "PARTITION_TABLE_NOT_FOUND", "no declared table has this name").
		Error(http.StatusConflict, "PARTITION_PASS_RUNNING", "another pass is running; a manual pass is refused, never queued").Build()
	a.ensureOp = httpfw.Post[EnsureBody, EnsureResult]("/api/admin/partitions/{table}/ensure", ensureRules).
		ID("ensureAdminPartitions").Summary("Create the partitions that cover a range").Tag("admin").
		Path(tablePath).Permission(manage).OperatorsOnly().
		Error(http.StatusNotFound, "PARTITION_TABLE_NOT_FOUND", "no declared table has this name").
		Error(http.StatusBadRequest, "PARTITION_RANGE_INVALID", "the range ends before it starts, or starts before 1970").
		Error(http.StatusBadRequest, "PARTITION_RANGE_TOO_LARGE", "the range needs more partitions than one call creates").
		Error(http.StatusConflict, "PARTITION_DETACHED_LEFTOVER", "a detached table has the name of a partition in the range").Build()
	adm.Section(admin.NewSection(SectionID, "Partitions", "/admin/partitions", View(app)).
		WithIcon("i-lucide-database").
		WithDescription("The partitioned tables: their partitions, retention and archives, for platform operators.").
		OperatorsOnly().
		Serving(a.listOp, a.getOp, a.archivesOp, a.passOp, a.ensureOp))
	return a
}

func notFound(table string) error {
	return httpfw.NewError(http.StatusNotFound, "PARTITION_TABLE_NOT_FOUND", "no declared table is named '"+table+"'")
}

func retentionOf(r partitions.RetentionStatus) Retention {
	out := Retention{Kind: RetentionKind(r.Kind)}
	if r.Kind != partitions.RetentionKeep {
		after := durationOf(r.After)
		out.After = &after
	}
	if r.Store != "" {
		store := r.Store
		out.Store = &store
	}
	return out
}

func lastPassOf(l *partitions.LastPass) *LastPass {
	if l == nil {
		return nil
	}
	return &LastPass{
		EndedAt: l.EndedAt, Ensure: StepStatus(l.Ensure), Retention: StepStatus(l.Retention), Archive: StepStatus(l.Archive),
		Created: int32(l.Created), Dropped: int32(l.Dropped), Archived: int32(l.Archived), //nolint:gosec // counts of a pass: a handful
	}
}

func databaseOf(name string) *string {
	if name == "" {
		return nil
	}
	return &name
}

func tableOf(st partitions.TableStatus) Table {
	out := Table{
		Name: st.Name, Database: databaseOf(st.Database), Key: st.Key, Interval: Interval(st.Interval.Code()), Ahead: int32(st.Ahead), //nolint:gosec // 1 to 24
		Retention: retentionOf(st.Retention), Healthy: st.Healthy, LastPass: lastPassOf(st.LastPass),
		Partitions: make([]Partition, 0, len(st.Partitions)), Detached: nonNil(st.Detached), Strays: nonNil(st.Strays),
		Bytes: st.Bytes, Rows: st.Rows,
	}
	for _, p := range st.Partitions {
		wp := Partition{Name: p.Name, From: p.From, To: p.To, Position: Position(p.Position), Bytes: p.Bytes, Rows: p.Rows, RemovalDue: p.RemovalDue}
		if p.Archived != nil {
			wp.Archived = &Archived{Objects: int32(p.Archived.Objects), Rows: p.Archived.Rows, Bytes: p.Archived.Bytes, ArchivedAt: p.Archived.ArchivedAt} //nolint:gosec // objects of one partition
		}
		out.Partitions = append(out.Partitions, wp)
	}
	return out
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

func summaryOf(st partitions.TableStatus) TableSummary {
	out := TableSummary{
		Name: st.Name, Database: databaseOf(st.Database), Key: st.Key, Interval: Interval(st.Interval.Code()), Ahead: int32(st.Ahead), //nolint:gosec // 1 to 24
		Retention: retentionOf(st.Retention), Healthy: st.Healthy, LastPass: lastPassOf(st.LastPass),
		PartitionCount: int32(len(st.Partitions)), Bytes: st.Bytes, Rows: st.Rows, //nolint:gosec // partitions of one table: hundreds at most
	}
	for i, p := range st.Partitions {
		if i == 0 { // oldest first
			from := p.From
			out.OldestFrom = &from
		}
		if p.RemovalDue != nil && (out.NextRemovalDue == nil || p.RemovalDue.Before(*out.NextRemovalDue)) {
			due := *p.RemovalDue
			out.NextRemovalDue = &due
		}
	}
	return out
}

func archiveOf(r partitions.ArchiveRecord) Archive {
	out := Archive{
		Table: r.Table, Partition: r.Partition, Objects: int32(r.Objects), Rows: r.Rows, Bytes: r.Bytes, ArchivedAt: r.ArchivedAt, Key: r.Key, //nolint:gosec // objects of one partition
	}
	if !r.From.IsZero() {
		from, to := r.From, r.To
		out.From, out.To = &from, &to
	}
	return out
}

// Build binds the five operations to the partitions module. Call it in the build function after
// [partitions.Build], and give the routes to httpfw.NewServer with the admin skeleton's.
func (a *Admin) Build(p *partitions.Partitions) (*httpfw.Routes, error) {
	if p == nil {
		return nil, errors.New("partitionsadmin: the partitions module is required")
	}
	rt := httpfw.NewRoutes("admin-partitions")

	rt.Bind(httpfw.Handle(a.listOp, func(ctx context.Context, _ fw.Context, _ httpfw.In[httpfw.NoBody]) (TableList, error) {
		all, err := p.Status(ctx)
		if err != nil {
			return TableList{}, err
		}
		out := TableList{Tables: make([]TableSummary, 0, len(all))}
		for _, st := range all {
			out.Tables = append(out.Tables, summaryOf(st))
		}
		return out, nil
	}))

	rt.Bind(httpfw.Handle(a.getOp, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (Table, error) {
		table := tablePath.Value(in.Params)
		st, ok, err := p.TableStatus(ctx, table)
		if err != nil {
			return Table{}, err
		}
		if !ok {
			return Table{}, notFound(table)
		}
		return tableOf(st), nil
	}))

	rt.Bind(httpfw.Handle(a.archivesOp, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (admin.Page[Archive], error) {
		table := tablePath.Value(in.Params)
		limit := int(max(1, min(admin.MaxLimit, limitQuery.Value(in.Params))))
		after, _ := archiveAfter.Value(in.Params)
		records, err := p.Archives(ctx, table, after, limit+1)
		if errors.Is(err, partitions.ErrTableNotDeclared) {
			return admin.Page[Archive]{}, notFound(table)
		}
		if err != nil {
			return admin.Page[Archive]{}, err
		}
		page := admin.Page[Archive]{Items: []Archive{}}
		for i, r := range records {
			if i == limit {
				last := records[limit-1].Partition
				page.NextCursor = &last
				break
			}
			page.Items = append(page.Items, archiveOf(r))
		}
		return page, nil
	}))

	rt.Bind(httpfw.Handle(a.passOp, func(ctx context.Context, cx fw.Context, in httpfw.In[PassBody]) (PassResults, error) {
		results, err := p.RunPass(ctx, cx, in.Body.Table)
		switch {
		case errors.Is(err, partitions.ErrTableNotDeclared):
			return PassResults{}, notFound(in.Body.Table)
		case errors.Is(err, partitions.ErrPassRunning):
			return PassResults{}, httpfw.NewError(http.StatusConflict, "PARTITION_PASS_RUNNING", "another pass is running; try again when it has ended")
		case err != nil:
			return PassResults{}, err
		}
		out := PassResults{Results: make([]PassResult, 0, len(results))}
		for _, r := range results {
			failed := make([]Step, len(r.Failed))
			for i, s := range r.Failed {
				failed[i] = Step(s)
			}
			out.Results = append(out.Results, PassResult{
				Table: r.Table, Created: int32(r.Created), Dropped: int32(r.Dropped), Archived: int32(r.Archived), Failed: failed, //nolint:gosec // counts of a pass
			})
		}
		return out, nil
	}))

	rt.Bind(httpfw.Handle(a.ensureOp, func(ctx context.Context, cx fw.Context, in httpfw.In[EnsureBody]) (EnsureResult, error) {
		table := tablePath.Value(in.Params)
		res, err := p.EnsureAs(ctx, cx, table, in.Body.From, in.Body.To)
		switch {
		case errors.Is(err, partitions.ErrTableNotDeclared):
			return EnsureResult{}, notFound(table)
		case errors.Is(err, partitions.ErrRangeInvalid):
			return EnsureResult{}, httpfw.NewError(http.StatusBadRequest, "PARTITION_RANGE_INVALID", rangeMessage(err))
		case errors.Is(err, partitions.ErrRangeTooLarge):
			return EnsureResult{}, httpfw.NewError(http.StatusBadRequest, "PARTITION_RANGE_TOO_LARGE", rangeMessage(err))
		case errors.Is(err, partitions.ErrDetachedLeftover):
			return EnsureResult{}, httpfw.NewError(http.StatusConflict, "PARTITION_DETACHED_LEFTOVER", rangeMessage(err))
		case err != nil:
			return EnsureResult{}, err
		}
		return EnsureResult{Created: int32(res.Created), AlreadyDue: int32(res.AlreadyDue)}, nil //nolint:gosec // at most 400
	}))
	return rt, nil
}

// rangeMessage is the refusal's own text without the sentinel's prefix ("partitions: range is
// invalid: to is before from" becomes "to is before from").
func rangeMessage(err error) string {
	text := err.Error()
	for _, sentinel := range []error{partitions.ErrRangeInvalid, partitions.ErrRangeTooLarge, partitions.ErrDetachedLeftover} {
		if rest, ok := strings.CutPrefix(text, sentinel.Error()+": "); ok {
			return rest
		}
	}
	return text
}
