package usecase_test

import (
	"io"

	"github.com/flowcatalyst/framework/go/metrics"
)

func writeMetrics(w io.Writer, e *env) error {
	return metrics.WritePrometheus(w, e.boot.Metrics().Snapshot())
}
