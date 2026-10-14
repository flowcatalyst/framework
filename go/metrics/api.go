// Package metrics is the framework's metrics registry and its Prometheus text exposition
// (background-and-metrics contract sections 2 and 3). The registry is explicit, never global:
// an application has one, from fw.Boot. Instruments are families with fixed label names; With
// returns the series for one combination of label values.
//
// The naming and label rules, the cardinality bound, the built-in names and the exposition bytes
// are the Java framework's.
package metrics

import (
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/flowcatalyst/framework/go/config"
)

// ContentType is the content type of the Prometheus text exposition format 0.0.4.
const ContentType = "text/plain; version=0.0.4; charset=utf-8"

// DefaultBuckets are a timer's default histogram bounds, in seconds.
var DefaultBuckets = []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10}

// Registry holds the application's metric families.
type Registry struct{ impl *registry }

// Option configures [NewRegistry].
type Option func(*options)

type options struct {
	maxSeries int
	noop      bool
	logger    *slog.Logger
}

// WithMaxSeries sets the most series one family may hold (1 to 100 000; default 1000).
func WithMaxSeries(n int) Option { return func(o *options) { o.maxSeries = n } }

// Noop makes a registry whose instruments record nothing and whose snapshot is empty (the noop
// driver). Names and labels are still checked.
func Noop() Option { return func(o *options) { o.noop = true } }

// WithLogger sets the logger for the WARN logged once per family when series are dropped.
func WithLogger(l *slog.Logger) Option { return func(o *options) { o.logger = l } }

// NewRegistry returns a registry (the memory driver unless [Noop]).
func NewRegistry(opts ...Option) (*Registry, error) { return newRegistry(opts) }

// Readable reports whether Snapshot returns what was recorded (false for the noop driver).
func (r *Registry) Readable() bool { return r.impl.recording }

// Counter returns the counter family name (ending in _total), creating it on first use; asking
// again with the same help and label names returns the same family.
func (r *Registry) Counter(name, help string, labelNames ...string) (*Counter, error) {
	return r.impl.counter(name, help, labelNames)
}

// Gauge returns the gauge family name (ending in neither _total nor _seconds).
func (r *Registry) Gauge(name, help string, labelNames ...string) (*Gauge, error) {
	return r.impl.gauge(name, help, labelNames)
}

// GaugeFunc registers a gauge read from f when a snapshot is taken. Once per name.
func (r *Registry) GaugeFunc(name, help string, f func() float64) error {
	return r.impl.gaugeFunc(name, help, f)
}

// Timer returns the timer family name (ending in _seconds) with the default buckets.
func (r *Registry) Timer(name, help string, labelNames ...string) (*Timer, error) {
	return r.impl.timer(name, help, DefaultBuckets, labelNames)
}

// TimerWithBuckets is [Registry.Timer] with explicit bucket bounds (strictly increasing,
// positive, finite, at most 50).
func (r *Registry) TimerWithBuckets(name, help string, buckets []float64, labelNames ...string) (*Timer, error) {
	return r.impl.timer(name, help, buckets, labelNames)
}

// Snapshot returns every series of every family.
func (r *Registry) Snapshot() Snapshot { return r.impl.snapshot() }

// Counter is a counter family.
type Counter struct{ f *family[*counterCell] }

// With returns the series for these label values (cut to 200 characters). It panics on a wrong
// number of values, a programming error.
func (c *Counter) With(labelValues ...string) *CounterSeries {
	return &CounterSeries{cell: c.f.series(labelValues)}
}

// Inc adds one to a family without labels.
func (c *Counter) Inc() { c.With().Inc() }

// Add adds v (not negative) to a family without labels.
func (c *Counter) Add(v float64) { c.With().Add(v) }

// CounterSeries is one series of a counter.
type CounterSeries struct{ cell *counterCell }

// Inc adds one.
func (s *CounterSeries) Inc() {
	if s.cell != nil {
		s.cell.add(1)
	}
}

// Add adds v (not negative; a negative amount is ignored).
func (s *CounterSeries) Add(v float64) {
	if s.cell != nil {
		s.cell.add(v)
	}
}

// Gauge is a gauge family.
type Gauge struct{ f *family[*gaugeCell] }

// With returns the series for these label values.
func (g *Gauge) With(labelValues ...string) *GaugeSeries {
	return &GaugeSeries{cell: g.f.series(labelValues)}
}

// Set sets a family without labels.
func (g *Gauge) Set(v float64) { g.With().Set(v) }

// Add adds d to a family without labels.
func (g *Gauge) Add(d float64) { g.With().Add(d) }

// GaugeSeries is one series of a gauge.
type GaugeSeries struct{ cell *gaugeCell }

// Set sets the value.
func (s *GaugeSeries) Set(v float64) {
	if s.cell != nil {
		s.cell.set(v)
	}
}

// Add adds d (negative to decrease).
func (s *GaugeSeries) Add(d float64) {
	if s.cell != nil {
		s.cell.add(d)
	}
}

// Timer is a timer family, exported as a histogram in seconds.
type Timer struct{ f *family[*timerCell] }

// With returns the series for these label values.
func (t *Timer) With(labelValues ...string) *TimerSeries {
	return &TimerSeries{cell: t.f.series(labelValues)}
}

// Record records a duration on a family without labels.
func (t *Timer) Record(d time.Duration) { t.With().Record(d) }

// TimerSeries is one series of a timer.
type TimerSeries struct{ cell *timerCell }

// Record records d (a negative duration records zero).
func (s *TimerSeries) Record(d time.Duration) {
	if s.cell != nil {
		s.cell.record(d)
	}
}

// Snapshot is every family and series at one moment: families sorted by name, each with its
// series sorted by label values.
type Snapshot struct {
	// Families are the metric families.
	Families []Family
}

// Type is an instrument kind, as the exposition format names it.
type Type int

// The instrument kinds.
const (
	// TypeCounter is a counter.
	TypeCounter Type = iota
	// TypeGauge is a gauge.
	TypeGauge
	// TypeHistogram is a timer, exported as a histogram.
	TypeHistogram
)

// Family is one metric family in a [Snapshot].
type Family struct {
	// Name is the metric name.
	Name string
	// Help is the help text.
	Help string
	// Type is the instrument kind.
	Type Type
	// LabelNames are the label names, in order.
	LabelNames []string
	// Buckets are a histogram's upper bounds; nil otherwise.
	Buckets []float64
	// Samples holds one entry per series.
	Samples []Sample
}

// Sample is one series in a [Snapshot].
type Sample struct {
	// LabelValues are the label values, in the order of the family's label names.
	LabelValues []string
	// Value is the value of a counter or gauge; for a histogram, the sum.
	Value float64
	// CumulativeCounts, for a histogram, is the number of observations at or below each bucket
	// bound, in bucket order; nil otherwise.
	CumulativeCounts []uint64
	// Count, for a histogram, is the number of observations; zero otherwise.
	Count uint64
}

// WritePrometheus writes s in the text exposition format 0.0.4, byte for byte as the Java
// framework's PrometheusText.
func WritePrometheus(w io.Writer, s Snapshot) error { return writePrometheus(w, s) }

// Handler serves the registry's snapshot as Prometheus text. With a bearer token, a request
// without "Authorization: Bearer <token>" (compared as SHA-256 digests in constant time) gets
// 401 with the framework's error envelope {"error":"UNAUTHENTICATED","message":"a bearer token
// is required"}.
func Handler(r *Registry, bearerToken *config.Secret) http.Handler { return newHandler(r, bearerToken) }
