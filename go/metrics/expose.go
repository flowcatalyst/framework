package metrics

import (
	"bytes"
	"crypto/sha256"
	"crypto/subtle"
	"io"
	"math"
	"net/http"
	"strconv"
	"strings"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// writeSnapshot renders s as the Prometheus text exposition format 0.0.4.
func writeSnapshot(w io.Writer, s Snapshot) error {
	var b strings.Builder
	for i := range s.Families {
		f := &s.Families[i]
		b.WriteString("# HELP " + f.Name + " " + escapeHelp(f.Help) + "\n")
		b.WriteString("# TYPE " + f.Name + " " + typeName(f.Type) + "\n")
		for j := range f.Samples {
			sm := &f.Samples[j]
			if f.Type == TypeHistogram {
				writeHistogram(&b, f, sm)
			} else {
				writeLine(&b, f.Name, f.LabelNames, sm.LabelValues, "", false, sm.Value)
			}
		}
	}
	_, err := io.WriteString(w, b.String())
	return err
}

func writeHistogram(b *strings.Builder, f *Family, s *Sample) {
	for i, bound := range f.Buckets {
		writeLine(b, f.Name+"_bucket", f.LabelNames, s.LabelValues, number(bound), true, float64(s.CumulativeCounts[i]))
	}
	writeLine(b, f.Name+"_bucket", f.LabelNames, s.LabelValues, "+Inf", true, float64(s.Count))
	writeLine(b, f.Name+"_sum", f.LabelNames, s.LabelValues, "", false, s.Value)
	writeLine(b, f.Name+"_count", f.LabelNames, s.LabelValues, "", false, float64(s.Count))
}

func writeLine(b *strings.Builder, name string, labelNames, labelValues []string, le string, hasLE bool, value float64) {
	b.WriteString(name)
	if len(labelNames) > 0 || hasLE {
		b.WriteByte('{')
		for i, n := range labelNames {
			if i > 0 {
				b.WriteByte(',')
			}
			b.WriteString(n + `="` + escapeValue(labelValues[i]) + `"`)
		}
		if hasLE {
			if len(labelNames) > 0 {
				b.WriteByte(',')
			}
			b.WriteString(`le="` + le + `"`)
		}
		b.WriteByte('}')
	}
	b.WriteString(" " + number(value) + "\n")
}

func typeName(t Type) string {
	switch t {
	case TypeCounter:
		return "counter"
	case TypeGauge:
		return "gauge"
	case TypeHistogram:
		return "histogram"
	}
	return "untyped"
}

// number formats a value as Java's PrometheusText does: integral values below 1e15 without a
// fraction, others as Double.toString, and +Inf, -Inf, NaN.
func number(v float64) string {
	switch {
	case math.IsNaN(v):
		return "NaN"
	case math.IsInf(v, 1):
		return "+Inf"
	case math.IsInf(v, -1):
		return "-Inf"
	case v == math.Trunc(v) && math.Abs(v) < 1e15:
		return strconv.FormatInt(int64(v), 10)
	}
	return javafmt.Double(v)
}

func escapeHelp(s string) string {
	return strings.NewReplacer(`\`, `\\`, "\n", `\n`).Replace(s)
}

func escapeValue(s string) string {
	return strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`).Replace(s)
}

const unauthenticatedBody = `{"error":"UNAUTHENTICATED","message":"a bearer token is required"}`

func newHandler(r *Registry, bearerToken *config.Secret) http.Handler {
	var expected [sha256.Size]byte
	protected := false
	if bearerToken != nil {
		protected = true
		expected = sha256.Sum256([]byte(bearerToken.Reveal()))
	}
	return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if protected && !authorized(req, expected) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = io.WriteString(w, unauthenticatedBody)
			return
		}
		var buf bytes.Buffer
		if err := writeSnapshot(&buf, r.Snapshot()); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", ContentType)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(buf.Bytes())
	})
}

func authorized(req *http.Request, expected [sha256.Size]byte) bool {
	values := req.Header.Values("Authorization")
	if len(values) != 1 {
		return false
	}
	const prefix = "Bearer "
	v := values[0]
	if len(v) < len(prefix) || !strings.EqualFold(v[:len(prefix)], prefix) {
		return false
	}
	got := sha256.Sum256([]byte(v[len(prefix):]))
	return subtle.ConstantTimeCompare(expected[:], got[:]) == 1
}
