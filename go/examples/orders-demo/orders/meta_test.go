package orders_test

import (
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
)

var seededType = event.MustType("orders:test:order:seeded")

type fwMeta struct {
	Meta event.Metadata `json:"-"`
}

func (m fwMeta) Metadata() event.Metadata { return m.Meta }

func newMeta(cx fw.Context, subject string) fwMeta {
	return fwMeta{Meta: event.NewFactory(fw.SystemClock(), ids).Metadata(cx, seededType, subject)}
}
