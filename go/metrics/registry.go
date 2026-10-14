package metrics

import (
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"
)

const (
	nameMax        = 200
	helpMax        = 500
	labelsMax      = 10
	valueMax       = 200
	bucketsMax     = 50
	maxSeriesLimit = 100_000
	defaultSeries  = 1000
	droppedName    = "fw_metrics_dropped_series_total"
)

// cell is the storage of one series.
type cell interface {
	sample(labelValues []string) Sample
}

// anyFamily is what the registry keeps of a family whatever its cell type.
type anyFamily interface {
	definition() *definition
	snapshot() Family
}

// definition is what makes two creations of a family "the same".
type definition struct {
	name       string
	help       string
	typ        Type
	labelNames []string
	buckets    []float64
	callback   func() float64
}

func (d *definition) sameAs(o *definition) bool {
	return d.callback == nil && o.callback == nil && d.typ == o.typ && d.help == o.help &&
		slices.Equal(d.labelNames, o.labelNames) && slices.Equal(d.buckets, o.buckets)
}

type registry struct {
	recording bool
	maxSeries int
	logger    *slog.Logger

	mu       sync.Mutex
	families map[string]anyFamily
	dropped  *family[*counterCell]
}

func newRegistry(opts []Option) (*Registry, error) {
	o := options{maxSeries: defaultSeries}
	for _, opt := range opts {
		opt(&o)
	}
	if o.maxSeries < 1 || o.maxSeries > maxSeriesLimit {
		return nil, fmt.Errorf("max series must be 1 to %d", maxSeriesLimit)
	}
	logger := o.logger
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	r := &registry{recording: !o.noop, maxSeries: o.maxSeries, logger: logger, families: map[string]anyFamily{}}
	if r.recording {
		d, err := register(r, droppedName, "Recordings dropped because a metric reached app.metrics.max-series.",
			TypeCounter, []string{"metric"}, nil, func() *counterCell { return &counterCell{} })
		if err != nil {
			return nil, err
		}
		r.dropped = d
	}
	return &Registry{impl: r}, nil
}

func (r *registry) counter(name, help string, labelNames []string) (*Counter, error) {
	if !strings.HasSuffix(name, "_total") {
		return nil, fmt.Errorf("counter name must end in _total: %s", name)
	}
	f, err := register(r, name, help, TypeCounter, labelNames, nil, func() *counterCell { return &counterCell{} })
	if err != nil {
		return nil, err
	}
	return &Counter{f: f}, nil
}

func (r *registry) gauge(name, help string, labelNames []string) (*Gauge, error) {
	if err := checkGaugeName(name); err != nil {
		return nil, err
	}
	f, err := register(r, name, help, TypeGauge, labelNames, nil, func() *gaugeCell { return &gaugeCell{} })
	if err != nil {
		return nil, err
	}
	return &Gauge{f: f}, nil
}

func (r *registry) gaugeFunc(name, help string, f func() float64) error {
	if err := checkGaugeName(name); err != nil {
		return err
	}
	if err := checkHelp(name, help); err != nil {
		return err
	}
	if f == nil {
		return fmt.Errorf("metric %s: the gauge function must not be nil", name)
	}
	fam := &family[*gaugeCell]{
		reg:     r,
		def:     &definition{name: name, help: help, typ: TypeGauge, callback: f},
		series_: map[string]*entry[*gaugeCell]{},
		factory: func() *gaugeCell { return &gaugeCell{} },
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.families[name]; ok {
		return fmt.Errorf("metric %s already exists; a callback gauge can be registered once", name)
	}
	r.families[name] = fam
	return nil
}

func (r *registry) timer(name, help string, buckets []float64, labelNames []string) (*Timer, error) {
	if !strings.HasSuffix(name, "_seconds") {
		return nil, fmt.Errorf("timer name must end in _seconds: %s", name)
	}
	if err := checkBuckets(name, buckets); err != nil {
		return nil, err
	}
	bounds := slices.Clone(buckets)
	f, err := register(r, name, help, TypeHistogram, labelNames, bounds, func() *timerCell { return newTimerCell(bounds) })
	if err != nil {
		return nil, err
	}
	return &Timer{f: f}, nil
}

// register creates a family or returns the identical one that exists.
func register[S cell](r *registry, name, help string, typ Type, labelNames []string, buckets []float64, factory func() S) (*family[S], error) {
	if err := checkName(name); err != nil {
		return nil, err
	}
	if err := checkHelp(name, help); err != nil {
		return nil, err
	}
	if err := checkLabels(name, labelNames); err != nil {
		return nil, err
	}
	def := &definition{name: name, help: help, typ: typ, labelNames: slices.Clone(labelNames), buckets: slices.Clone(buckets)}
	r.mu.Lock()
	defer r.mu.Unlock()
	if existing, ok := r.families[name]; ok {
		same, isSame := existing.(*family[S])
		if !isSame || !existing.definition().sameAs(def) {
			return nil, fmt.Errorf("metric %s already exists with another type, help, label names or buckets", name)
		}
		return same, nil
	}
	f := &family[S]{reg: r, def: def, series_: map[string]*entry[S]{}, factory: factory}
	r.families[name] = f
	return f, nil
}

func checkName(name string) error {
	if len(name) > nameMax || !validName(name, '_') {
		return fmt.Errorf("metric name must match [a-z][a-z0-9_]*, at most %d characters: %s", nameMax, name)
	}
	return nil
}

// validName reports whether s is [a-z][a-z0-9<sep>]*.
func validName(s string, sep byte) bool {
	if s == "" || s[0] < 'a' || s[0] > 'z' {
		return false
	}
	for i := 1; i < len(s); i++ {
		c := s[i]
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != sep {
			return false
		}
	}
	return true
}

func checkGaugeName(name string) error {
	if err := checkName(name); err != nil {
		return err
	}
	if strings.HasSuffix(name, "_total") || strings.HasSuffix(name, "_seconds") {
		return fmt.Errorf("a gauge name must not end in _total or _seconds (use a counter or a timer): %s", name)
	}
	return nil
}

func checkHelp(name, help string) error {
	if strings.TrimSpace(help) == "" || utf8.RuneCountInString(help) > helpMax {
		return fmt.Errorf("metric %s: help must be 1 to %d characters", name, helpMax)
	}
	return nil
}

func checkLabels(name string, labelNames []string) error {
	if len(labelNames) > labelsMax {
		return fmt.Errorf("metric %s: at most %d labels", name, labelsMax)
	}
	seen := map[string]bool{}
	for _, l := range labelNames {
		if !validName(l, '_') || len(l) > nameMax || strings.HasPrefix(l, "__") || l == "le" || l == "quantile" {
			return fmt.Errorf("metric %s: label name must match [a-z][a-z0-9_]*, not start with __ and not be le or quantile: %s", name, l)
		}
		if seen[l] {
			return fmt.Errorf("metric %s: label %s twice", name, l)
		}
		seen[l] = true
	}
	return nil
}

func checkBuckets(name string, buckets []float64) error {
	if len(buckets) == 0 || len(buckets) > bucketsMax {
		return fmt.Errorf("timer %s: 1 to %d bucket bounds", name, bucketsMax)
	}
	previous := 0.0
	for _, b := range buckets {
		if !(b > previous) || math.IsInf(b, 0) {
			return fmt.Errorf("timer %s: bucket bounds must be positive, finite and strictly increasing", name)
		}
		previous = b
	}
	return nil
}

// cut shortens a label value to valueMax characters.
func cut(v string) string {
	if len(v) <= valueMax {
		return v
	}
	if utf8.RuneCountInString(v) <= valueMax {
		return v
	}
	n := 0
	for i := range v {
		if n == valueMax {
			return v[:i]
		}
		n++
	}
	return v
}

// seriesKey encodes values so that different combinations never share a key.
func seriesKey(values []string) string {
	var b strings.Builder
	for _, v := range values {
		fmt.Fprintf(&b, "%d:%s,", len(v), v)
	}
	return b.String()
}

func (r *registry) snapshot() Snapshot {
	if !r.recording {
		return Snapshot{}
	}
	r.mu.Lock()
	all := make([]anyFamily, 0, len(r.families))
	for _, f := range r.families {
		all = append(all, f)
	}
	r.mu.Unlock()
	slices.SortFunc(all, func(a, b anyFamily) int { return strings.Compare(a.definition().name, b.definition().name) })
	out := Snapshot{Families: make([]Family, 0, len(all))}
	for _, f := range all {
		out.Families = append(out.Families, f.snapshot())
	}
	return out
}

type entry[S cell] struct {
	values []string
	cell   S
}

type family[S cell] struct {
	reg     *registry
	def     *definition
	factory func() S

	mu      sync.RWMutex
	series_ map[string]*entry[S]

	warned         atomic.Bool
	callbackWarned atomic.Bool
}

func (f *family[S]) definition() *definition { return f.def }

// series returns the cell for the values, or the zero S (nil) when nothing is recorded: the
// registry is a noop or the family is full. A wrong number of values panics.
func (f *family[S]) series(values []string) S {
	var none S
	if len(values) != len(f.def.labelNames) {
		panic(fmt.Sprintf("metric %s has labels [%s]; got %d values", f.def.name, strings.Join(f.def.labelNames, ", "), len(values)))
	}
	if !f.reg.recording {
		return none
	}
	cleaned := make([]string, len(values))
	for i, v := range values {
		cleaned[i] = cut(v)
	}
	key := seriesKey(cleaned)
	f.mu.RLock()
	e := f.series_[key]
	f.mu.RUnlock()
	if e != nil {
		return e.cell
	}
	f.mu.Lock()
	e = f.series_[key]
	if e == nil && len(f.series_) < f.reg.maxSeries {
		e = &entry[S]{values: cleaned, cell: f.factory()}
		f.series_[key] = e
	}
	f.mu.Unlock()
	if e == nil {
		f.dropped()
		return none
	}
	return e.cell
}

func (f *family[S]) dropped() {
	if f.warned.CompareAndSwap(false, true) {
		f.reg.logger.Warn("metric reached the series limit; new label combinations are dropped",
			slog.String("metric", f.def.name), slog.Int("maxSeries", f.reg.maxSeries))
	}
	if d := f.reg.dropped; d != nil && d.def.name != f.def.name {
		if c := d.series([]string{f.def.name}); c != nil {
			c.add(1)
		}
	}
}

func (f *family[S]) snapshot() Family {
	out := Family{Name: f.def.name, Help: f.def.help, Type: f.def.typ, LabelNames: slices.Clone(f.def.labelNames), Buckets: slices.Clone(f.def.buckets)}
	if cb := f.def.callback; cb != nil {
		if v, ok := f.callGauge(cb); ok {
			out.Samples = append(out.Samples, Sample{Value: v})
		}
	}
	f.mu.RLock()
	for _, e := range f.series_ {
		out.Samples = append(out.Samples, e.cell.sample(e.values))
	}
	f.mu.RUnlock()
	slices.SortFunc(out.Samples, func(a, b Sample) int { return slices.Compare(a.LabelValues, b.LabelValues) })
	return out
}

// callGauge reads a callback gauge; a panic leaves the gauge out (logged once, type only).
func (f *family[S]) callGauge(cb func() float64) (v float64, ok bool) {
	defer func() {
		if p := recover(); p != nil {
			ok = false
			if f.callbackWarned.CompareAndSwap(false, true) {
				f.reg.logger.Warn("callback gauge panicked; it is left out",
					slog.String("metric", f.def.name), slog.String("panic", fmt.Sprintf("%T", p)))
			}
		}
	}()
	return cb(), true
}

// atomicFloat is a float64 updated atomically.
type atomicFloat struct{ bits atomic.Uint64 }

func (a *atomicFloat) load() float64   { return math.Float64frombits(a.bits.Load()) }
func (a *atomicFloat) store(v float64) { a.bits.Store(math.Float64bits(v)) }
func (a *atomicFloat) add(d float64) {
	for {
		old := a.bits.Load()
		if a.bits.CompareAndSwap(old, math.Float64bits(math.Float64frombits(old)+d)) {
			return
		}
	}
}

type counterCell struct{ v atomicFloat }

func (c *counterCell) add(amount float64) {
	if amount >= 0 && !math.IsInf(amount, 0) {
		c.v.add(amount)
	}
}

func (c *counterCell) sample(values []string) Sample {
	return Sample{LabelValues: values, Value: c.v.load()}
}

type gaugeCell struct{ v atomicFloat }

func (g *gaugeCell) set(v float64) { g.v.store(v) }
func (g *gaugeCell) add(d float64) { g.v.add(d) }
func (g *gaugeCell) sample(values []string) Sample {
	return Sample{LabelValues: values, Value: g.v.load()}
}

type timerCell struct {
	bounds    []float64
	perBucket []atomic.Uint64
	count     atomic.Uint64
	sum       atomicFloat
}

func newTimerCell(bounds []float64) *timerCell {
	return &timerCell{bounds: bounds, perBucket: make([]atomic.Uint64, len(bounds))}
}

func (t *timerCell) record(d time.Duration) {
	seconds := float64(max(0, int64(d))) / 1e9
	for i, b := range t.bounds {
		if seconds <= b {
			t.perBucket[i].Add(1)
			break
		}
	}
	t.count.Add(1)
	t.sum.add(seconds)
}

func (t *timerCell) sample(values []string) Sample {
	cumulative := make([]uint64, len(t.bounds))
	var running uint64
	for i := range t.perBucket {
		running += t.perBucket[i].Load()
		cumulative[i] = running
	}
	// count and buckets are read separately; never report fewer observations than the buckets.
	return Sample{LabelValues: values, Value: t.sum.load(), CumulativeCounts: cumulative, Count: max(t.count.Load(), running)}
}

var errNilWriter = errors.New("metrics: nil writer")

func writePrometheus(w io.Writer, s Snapshot) error {
	if w == nil {
		return errNilWriter
	}
	return writeSnapshot(w, s)
}
