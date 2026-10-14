package metrics_test

import (
	"bytes"
	"context"
	"log/slog"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/metrics"
)

func newReg(t *testing.T, opts ...metrics.Option) *metrics.Registry {
	t.Helper()
	r, err := metrics.NewRegistry(opts...)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func mustCounter(t *testing.T, r *metrics.Registry, name, help string, labels ...string) *metrics.Counter {
	t.Helper()
	c, err := r.Counter(name, help, labels...)
	if err != nil || c == nil {
		t.Fatal(err)
		return nil
	}
	return c
}

func mustGauge(t *testing.T, r *metrics.Registry, name, help string, labels ...string) *metrics.Gauge {
	t.Helper()
	g, err := r.Gauge(name, help, labels...)
	if err != nil || g == nil {
		t.Fatal(err)
		return nil
	}
	return g
}

func mustTimer(t *testing.T, r *metrics.Registry, name, help string, buckets []float64, labels ...string) *metrics.Timer {
	t.Helper()
	var tm *metrics.Timer
	var err error
	if buckets == nil {
		tm, err = r.Timer(name, help, labels...)
	} else {
		tm, err = r.TimerWithBuckets(name, help, buckets, labels...)
	}
	if err != nil || tm == nil {
		t.Fatal(err)
		return nil
	}
	return tm
}

func family(t *testing.T, r *metrics.Registry, name string) metrics.Family {
	t.Helper()
	for _, f := range r.Snapshot().Families {
		if f.Name == name {
			return f
		}
	}
	t.Fatalf("family %s not found", name)
	return metrics.Family{}
}

func mustErr(t *testing.T, err error, want string) {
	t.Helper()
	if err == nil {
		t.Fatalf("expected error containing %q", want)
	}
	if !strings.Contains(err.Error(), want) {
		t.Fatalf("error %q does not contain %q", err, want)
	}
}

func TestCountersAddAndIgnoreNegativeNaNAndInfiniteAmounts(t *testing.T) {
	r := newReg(t)
	c, err := r.Counter("app_orders_total", "Orders placed.", "kind")
	if err != nil {
		t.Fatal(err)
	}
	web := c.With("web")
	web.Inc()
	web.Add(2.5)
	web.Add(-3)
	web.Add(math.NaN())
	web.Add(math.Inf(1))
	c.With("api").Inc()
	f := family(t, r, "app_orders_total")
	if f.Type != metrics.TypeCounter || len(f.Samples) != 2 {
		t.Fatalf("family %+v", f)
	}
	if f.Samples[0].LabelValues[0] != "api" || f.Samples[0].Value != 1 {
		t.Fatalf("api %+v", f.Samples[0])
	}
	if f.Samples[1].LabelValues[0] != "web" || f.Samples[1].Value != 3.5 {
		t.Fatalf("web %+v", f.Samples[1])
	}
}

func TestLabellessCounterRecordsOnTheFamily(t *testing.T) {
	r := newReg(t)
	c := mustCounter(t, r, "app_plain_total", "Plain.")
	c.Inc()
	c.Add(2)
	if v := family(t, r, "app_plain_total").Samples[0].Value; v != 3 {
		t.Fatalf("got %v", v)
	}
}

func TestGaugesSetAndAdd(t *testing.T) {
	r := newReg(t)
	g := mustGauge(t, r, "app_queue_depth", "Queue depth.")
	g.Set(5)
	g.With().Add(2)
	g.Add(-4)
	if v := family(t, r, "app_queue_depth").Samples[0].Value; v != 3 {
		t.Fatalf("got %v", v)
	}
	lg := mustGauge(t, r, "app_lg", "Labelled.", "a")
	lg.With("x").Set(1.5)
	lg.With("x").Add(1)
	if v := family(t, r, "app_lg").Samples[0].Value; v != 2.5 {
		t.Fatalf("got %v", v)
	}
}

func TestCallbackGaugesAreReadOnSnapshotAndRegisteredOnce(t *testing.T) {
	r := newReg(t)
	var mu sync.Mutex
	depth := 7.0
	read := func() float64 { mu.Lock(); defer mu.Unlock(); return depth }
	if err := r.GaugeFunc("app_pool_size", "Pool size.", read); err != nil {
		t.Fatal(err)
	}
	if v := family(t, r, "app_pool_size").Samples[0].Value; v != 7 {
		t.Fatalf("got %v", v)
	}
	mu.Lock()
	depth = 9
	mu.Unlock()
	if v := family(t, r, "app_pool_size").Samples[0].Value; v != 9 {
		t.Fatalf("got %v", v)
	}
	mustErr(t, r.GaugeFunc("app_pool_size", "Pool size.", read), "already exists")
	_, err := r.Gauge("app_pool_size", "Pool size.")
	mustErr(t, err, "already exists")
	_, _ = r.Gauge("app_g", "G.")
	mustErr(t, r.GaugeFunc("app_g", "G.", read), "already exists")
	mustErr(t, r.GaugeFunc("Bad", "G.", read), "metric name must match")
	mustErr(t, r.GaugeFunc("app_x_total", "G.", read), "a gauge name must not end in")
	mustErr(t, r.GaugeFunc("app_h", " ", read), "help must be 1 to 500")
}

func TestACallbackThatPanicsIsLeftOutAndWarnedOnce(t *testing.T) {
	var buf bytes.Buffer
	r := newReg(t, metrics.WithLogger(slog.New(slog.NewTextHandler(&buf, nil))))
	_ = r.GaugeFunc("app_broken", "Broken.", func() float64 { panic("secret") })
	for range 3 {
		if n := len(family(t, r, "app_broken").Samples); n != 0 {
			t.Fatalf("samples %d", n)
		}
	}
	if n := strings.Count(buf.String(), "level=WARN"); n != 1 {
		t.Fatalf("warnings %d: %s", n, buf.String())
	}
	if strings.Contains(buf.String(), "secret") {
		t.Fatal("panic value leaked")
	}
}

func TestTimersFillCumulativeBucketsSumAndCount(t *testing.T) {
	r := newReg(t)
	tm, err := r.TimerWithBuckets("app_job_seconds", "Job time.", []float64{0.1, 1}, "job")
	if err != nil {
		t.Fatal(err)
	}
	s := tm.With("export")
	s.Record(50 * time.Millisecond)
	s.Record(100 * time.Millisecond)
	s.Record(500 * time.Millisecond)
	s.Record(3 * time.Second)
	s.Record(-5)
	s.Record(200 * time.Millisecond)
	sm := family(t, r, "app_job_seconds").Samples[0]
	if len(sm.CumulativeCounts) != 2 || sm.CumulativeCounts[0] != 3 || sm.CumulativeCounts[1] != 5 {
		t.Fatalf("cumulative %v", sm.CumulativeCounts)
	}
	if sm.Count != 6 {
		t.Fatalf("count %d", sm.Count)
	}
	if math.Abs(sm.Value-3.85) > 1e-9 {
		t.Fatalf("sum %v", sm.Value)
	}
}

func TestTimerDefaultBucketsAndLabellessRecord(t *testing.T) {
	r := newReg(t)
	tm := mustTimer(t, r, "app_t_seconds", "T.", nil)
	tm.Record(time.Second)
	f := family(t, r, "app_t_seconds")
	if len(f.Buckets) != 11 || f.Buckets[0] != 0.005 || f.Buckets[10] != 10 {
		t.Fatalf("buckets %v", f.Buckets)
	}
	if f.Samples[0].Count != 1 || f.Samples[0].CumulativeCounts[7] != 1 || f.Samples[0].CumulativeCounts[6] != 0 {
		t.Fatalf("sample %+v", f.Samples[0])
	}
}

func TestNamingRules(t *testing.T) {
	r := newReg(t)
	for _, bad := range []string{"Orders_total", "1x_total", "a-b_total", strings.Repeat("x", 196) + "_total"} {
		_, err := r.Counter(bad, "h")
		mustErr(t, err, "metric name must match [a-z][a-z0-9_]*, at most 200 characters: "+bad)
	}
	_, err := r.Gauge("", "h")
	mustErr(t, err, "metric name must match")
	_, err = r.Counter("app_orders", "h")
	mustErr(t, err, "counter name must end in _total: app_orders")
	_, err = r.Timer("app_job", "h")
	mustErr(t, err, "timer name must end in _seconds: app_job")
	_, err = r.Gauge("app_x_total", "h")
	mustErr(t, err, "a gauge name must not end in _total or _seconds")
	_, err = r.Gauge("app_x_seconds", "h")
	mustErr(t, err, "a gauge name must not end in _total or _seconds")
	_, err = r.Counter("app_x_total", " ")
	mustErr(t, err, "metric app_x_total: help must be 1 to 500 characters")
	_, err = r.Counter("app_x_total", strings.Repeat("h", 501))
	mustErr(t, err, "help must be 1 to 500")
	if _, err = r.Counter("app_ok_total", strings.Repeat("h", 500)); err != nil {
		t.Fatal(err)
	}
}

func TestLabelNameRules(t *testing.T) {
	r := newReg(t)
	for _, bad := range []string{"le", "quantile", "__x", "Method", "a-b", "1a"} {
		_, err := r.Counter("app_l_total", "h", bad)
		mustErr(t, err, "label name must match")
	}
	_, err := r.Counter("app_l_total", "h", "a", "a")
	mustErr(t, err, "label a twice")
	_, err = r.Counter("app_l_total", "h", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")
	mustErr(t, err, "at most 10 labels")
	if _, err = r.Counter("app_l_total", "h", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j"); err != nil {
		t.Fatal(err)
	}
}

func TestBucketRules(t *testing.T) {
	r := newReg(t)
	for _, b := range [][]float64{nil, {1, 1}, {0, 1}, {2, 1}, {1, math.Inf(1)}, {math.NaN()}, make([]float64, 51)} {
		_, err := r.TimerWithBuckets("app_b_seconds", "h", b)
		mustErr(t, err, "timer app_b_seconds:")
	}
	fifty := make([]float64, 50)
	for i := range fifty {
		fifty[i] = float64(i + 1)
	}
	if _, err := r.TimerWithBuckets("app_b50_seconds", "h", fifty); err != nil {
		t.Fatal(err)
	}
}

func TestCreatingAgainReturnsTheSameFamilyAndAnythingElseIsRefused(t *testing.T) {
	r := newReg(t)
	a := mustCounter(t, r, "app_same_total", "Same.", "x")
	b, err := r.Counter("app_same_total", "Same.", "x")
	if err != nil {
		t.Fatal(err)
	}
	a.With("1").Inc()
	b.With("1").Inc()
	if v := family(t, r, "app_same_total").Samples[0].Value; v != 2 {
		t.Fatalf("got %v", v)
	}
	_, err = r.Counter("app_same_total", "Other help.", "x")
	mustErr(t, err, "metric app_same_total already exists with another type, help, label names or buckets")
	_, err = r.Counter("app_same_total", "Same.", "y")
	mustErr(t, err, "already exists")
	_, _ = r.TimerWithBuckets("app_tt_seconds", "T.", []float64{1, 2})
	_, err = r.TimerWithBuckets("app_tt_seconds", "T.", []float64{1, 3})
	mustErr(t, err, "already exists")
	if _, err = r.TimerWithBuckets("app_tt_seconds", "T.", []float64{1, 2}); err != nil {
		t.Fatal(err)
	}
	_, _ = r.Gauge("app_same", "G.")
	_, err = r.Timer("app_same_seconds", "T.")
	if err != nil {
		t.Fatal(err)
	}
}

func TestLabelValuesNeverFailButTheirNumberDoes(t *testing.T) {
	r := newReg(t)
	c := mustCounter(t, r, "app_v_total", "Values.", "a", "b")
	for name, f := range map[string]func(){
		"one value": func() { c.With("one") },
		"inc":       c.Inc,
		"three":     func() { c.With("1", "2", "3") },
	} {
		func() {
			defer func() {
				if recover() == nil {
					t.Errorf("%s: no panic", name)
				}
			}()
			f()
		}()
	}
	c.With("", strings.Repeat("x", 500)).Inc()
	s := family(t, r, "app_v_total").Samples[0]
	if s.LabelValues[0] != "" || len(s.LabelValues[1]) != 200 {
		t.Fatalf("values %q", s.LabelValues)
	}
}

func TestTheNumberOfSeriesIsBoundedAndDropsAreCountedAndWarnedOnce(t *testing.T) {
	var buf bytes.Buffer
	r := newReg(t, metrics.WithMaxSeries(3), metrics.WithLogger(slog.New(slog.NewTextHandler(&buf, nil))))
	d := family(t, r, "fw_metrics_dropped_series_total")
	if len(d.Samples) != 0 {
		t.Fatal("dropped counter has series from the start")
	}
	c := mustCounter(t, r, "app_users_total", "Per user, which is a mistake.", "user")
	for i := range 10 {
		c.With("u" + string(rune('0'+i))).Inc()
	}
	c.With("u0").Inc() // an existing series still records
	f := family(t, r, "app_users_total")
	if len(f.Samples) != 3 || f.Samples[0].Value != 2 {
		t.Fatalf("family %+v", f)
	}
	d = family(t, r, "fw_metrics_dropped_series_total")
	if len(d.Samples) != 1 || d.Samples[0].LabelValues[0] != "app_users_total" || d.Samples[0].Value != 7 {
		t.Fatalf("dropped %+v", d)
	}
	if n := strings.Count(buf.String(), "level=WARN"); n != 1 {
		t.Fatalf("warnings %d: %s", n, buf.String())
	}
	if !strings.Contains(buf.String(), "app_users_total") {
		t.Fatal("warning does not name the metric")
	}
}

func TestSeriesCreatedConcurrentlyNeverExceedTheBound(t *testing.T) {
	r := newReg(t, metrics.WithMaxSeries(50))
	c := mustCounter(t, r, "app_race_total", "Race.", "k")
	var wg sync.WaitGroup
	for g := range 16 {
		wg.Go(func() {
			for i := range 100 {
				c.With(string(rune('a'+g)) + strings.Repeat("z", i)).Inc()
			}
		})
	}
	wg.Wait()
	if n := len(family(t, r, "app_race_total").Samples); n != 50 {
		t.Fatalf("series %d", n)
	}
}

func TestConcurrentRecordingIsExact(t *testing.T) {
	r := newReg(t)
	c := mustCounter(t, r, "app_cc_total", "C.")
	g := mustGauge(t, r, "app_gg", "G.")
	tm := mustTimer(t, r, "app_cc_seconds", "T.", nil)
	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() {
			for range 1000 {
				c.Inc()
				g.Add(1)
				tm.Record(time.Millisecond)
			}
		})
	}
	wg.Wait()
	if v := family(t, r, "app_cc_total").Samples[0].Value; v != 8000 {
		t.Fatalf("counter %v", v)
	}
	if v := family(t, r, "app_gg").Samples[0].Value; v != 8000 {
		t.Fatalf("gauge %v", v)
	}
	if n := family(t, r, "app_cc_seconds").Samples[0].Count; n != 8000 {
		t.Fatalf("timer %v", n)
	}
}

func TestFwPrefixIsNotEnforcedAsInJava(t *testing.T) {
	r := newReg(t)
	if _, err := r.Counter("fw_mine_total", "Mine."); err != nil {
		t.Fatal(err)
	}
}

func TestMaxSeriesIsChecked(t *testing.T) {
	for _, n := range []int{0, -1, 100001} {
		_, err := metrics.NewRegistry(metrics.WithMaxSeries(n))
		mustErr(t, err, "max series must be 1 to 100000")
	}
	if _, err := metrics.NewRegistry(metrics.WithMaxSeries(100000)); err != nil {
		t.Fatal(err)
	}
}

func TestTheNoopDriverChecksNamesAndRecordsNothing(t *testing.T) {
	n := newReg(t, metrics.Noop())
	if n.Readable() || !newReg(t).Readable() {
		t.Fatal("readable")
	}
	_, err := n.Counter("bad", "h")
	mustErr(t, err, "must end in _total")
	c := mustCounter(t, n, "app_n_total", "N.", "a")
	func() {
		defer func() {
			if recover() == nil {
				t.Error("no panic for the wrong number of values")
			}
		}()
		c.With()
	}()
	c.With("x").Inc()
	tm := mustTimer(t, n, "app_n_seconds", "N.", nil)
	tm.Record(time.Second)
	g := mustGauge(t, n, "app_n", "N.")
	g.Set(4)
	_ = n.GaugeFunc("app_nf", "N.", func() float64 { return 1 })
	if len(n.Snapshot().Families) != 0 {
		t.Fatal("snapshot not empty")
	}
	var buf bytes.Buffer
	if err := metrics.WritePrometheus(&buf, n.Snapshot()); err != nil || buf.Len() != 0 {
		t.Fatal("noop renders nothing")
	}
}

func TestExpositionBytes(t *testing.T) {
	r := newReg(t)
	c := mustCounter(t, r, "app_requests_total", "Requests,\nby \\ path.", "route", "status")
	c.With("/b", "200").Add(2)
	c.With("/a \"quoted\"\nline\\", "500").Inc()
	g := mustGauge(t, r, "app_depth", "Depth.")
	g.Set(0.25)
	tm := mustTimer(t, r, "app_job_seconds", "Job.", []float64{0.5, 1}, "job")
	tm.With("x").Record(250 * time.Millisecond)
	tm.With("x").Record(2 * time.Second)
	var buf bytes.Buffer
	if err := metrics.WritePrometheus(&buf, r.Snapshot()); err != nil {
		t.Fatal(err)
	}
	want := `# HELP app_depth Depth.
# TYPE app_depth gauge
app_depth 0.25
# HELP app_job_seconds Job.
# TYPE app_job_seconds histogram
app_job_seconds_bucket{job="x",le="0.5"} 1
app_job_seconds_bucket{job="x",le="1"} 1
app_job_seconds_bucket{job="x",le="+Inf"} 2
app_job_seconds_sum{job="x"} 2.25
app_job_seconds_count{job="x"} 2
# HELP app_requests_total Requests,\nby \\ path.
# TYPE app_requests_total counter
app_requests_total{route="/a \"quoted\"\nline\\",status="500"} 1
app_requests_total{route="/b",status="200"} 2
# HELP fw_metrics_dropped_series_total Recordings dropped because a metric reached app.metrics.max-series.
# TYPE fw_metrics_dropped_series_total counter
`
	if buf.String() != want {
		t.Fatalf("got:\n%s\nwant:\n%s", buf.String(), want)
	}
}

func TestExpositionNumbers(t *testing.T) {
	r := newReg(t)
	values := map[string]float64{
		"app_n_int": 3, "app_n_neg": -2, "app_n_tenth": 0.1, "app_n_small": 1e-4, "app_n_big": 1e20,
		"app_n_pinf": math.Inf(1), "app_n_ninf": math.Inf(-1), "app_n_nan": math.NaN(),
		"app_n_edge": 123456789012345, "app_n_edge2": 1e15, "app_n_negzero": math.Copysign(0, -1), "app_n_mid": 1234567.5,
	}
	for name, v := range values {
		g := mustGauge(t, r, name, "N.")
		g.Set(v)
	}
	var buf bytes.Buffer
	_ = metrics.WritePrometheus(&buf, r.Snapshot())
	want := map[string]string{
		"app_n_int": "3", "app_n_neg": "-2", "app_n_tenth": "0.1", "app_n_small": "1.0E-4", "app_n_big": "1.0E20",
		"app_n_pinf": "+Inf", "app_n_ninf": "-Inf", "app_n_nan": "NaN",
		"app_n_edge": "123456789012345", "app_n_edge2": "1.0E15", "app_n_negzero": "0", "app_n_mid": "1234567.5",
	}
	for name, w := range want {
		if line := name + " " + w + "\n"; !strings.Contains(buf.String(), "\n"+line) {
			t.Errorf("missing %q in\n%s", line, buf.String())
		}
	}
}

func TestExpositionOfLabelledHistogramWithFractionalBounds(t *testing.T) {
	r := newReg(t)
	tm := mustTimer(t, r, "app_h_seconds", "H.", []float64{0.0001, 2.5})
	tm.Record(time.Millisecond)
	var buf bytes.Buffer
	_ = metrics.WritePrometheus(&buf, r.Snapshot())
	for _, line := range []string{
		"app_h_seconds_bucket{le=\"1.0E-4\"} 0\n", "app_h_seconds_bucket{le=\"2.5\"} 1\n",
		"app_h_seconds_bucket{le=\"+Inf\"} 1\n", "app_h_seconds_sum 0.001\n", "app_h_seconds_count 1\n",
	} {
		if !strings.Contains(buf.String(), line) {
			t.Errorf("missing %q in\n%s", line, buf.String())
		}
	}
}

func get(t *testing.T, h interface {
	ServeHTTP(http.ResponseWriter, *http.Request)
}, auth ...string,
) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/metrics", nil)
	for _, a := range auth {
		req.Header.Add("Authorization", a)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req.WithContext(context.Background()))
	return rec
}

func TestHandlerWithoutToken(t *testing.T) {
	r := newReg(t)
	c := mustCounter(t, r, "app_h_total", "H.")
	c.Inc()
	rec := get(t, metrics.Handler(r, nil))
	if rec.Code != 200 || rec.Header().Get("Content-Type") != metrics.ContentType {
		t.Fatalf("%d %v", rec.Code, rec.Header())
	}
	if !strings.Contains(rec.Body.String(), "app_h_total 1\n") {
		t.Fatal(rec.Body.String())
	}
}

func TestHandlerWithToken(t *testing.T) {
	r := newReg(t)
	secret := config.NewSecret("s3cret")
	h := metrics.Handler(r, &secret)
	denied := [][]string{{}, {"Bearer wrong"}, {"s3cret"}, {"Basic s3cret"}, {"Bearer s3cret", "Bearer s3cret"}, {"Bearer"}, {"Bearer  s3cret"}}
	for _, a := range denied {
		rec := get(t, h, a...)
		if rec.Code != 401 || rec.Header().Get("Content-Type") != "application/json" {
			t.Fatalf("%v: %d %v", a, rec.Code, rec.Header())
		}
		if rec.Body.String() != `{"error":"UNAUTHENTICATED","message":"a bearer token is required"}` {
			t.Fatalf("body %q", rec.Body.String())
		}
	}
	for _, a := range []string{"Bearer s3cret", "bearer s3cret", "BEARER s3cret"} {
		if rec := get(t, h, a); rec.Code != 200 {
			t.Fatalf("%q: %d", a, rec.Code)
		}
	}
}
