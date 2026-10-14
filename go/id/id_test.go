package id_test

import (
	"encoding/json/v2"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/id"
)

type orderKind struct{}

func (orderKind) Prefix() string { return "ord" }

type lineKind struct{}

func (lineKind) Prefix() string { return "lin" }

type badKind struct{}

func (badKind) Prefix() string { return "Bad" }

func TestTypedIDs(t *testing.T) {
	t.Parallel()
	ids := fw.NewIds(fwtest.NewClock(time.UnixMilli(1_760_000_000_000)))
	o := id.New[orderKind](ids)
	if !strings.HasPrefix(o.String(), "ord_") || len(o.String()) != 17 {
		t.Errorf("new id %q", o)
	}
	if _, err := id.Parse[lineKind](o.String()); !errors.Is(err, id.ErrInvalid) {
		t.Errorf("an order id parsed as a line id: %v", err)
	}
	back, err := id.Parse[orderKind](o.String())
	if err != nil || back != o {
		t.Errorf("round trip %v %v", back, err)
	}
	for _, bad := range []string{"", "ord_", "ord_0abcdefghjkmn", "ord-0ABCDEFGHJKMN", "ord_8000000000000"} {
		if _, err := id.Parse[orderKind](bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
	b, err := json.Marshal(struct {
		ID id.ID[orderKind] `json:"id"`
	}{o})
	if err != nil || string(b) != `{"id":"`+o.String()+`"}` {
		t.Errorf("JSON %s %v", b, err)
	}
	if _, err := (id.ID[orderKind]{}).Value(); err == nil {
		t.Error("the zero id can be stored")
	}
	var scanned id.ID[orderKind]
	if err := scanned.Scan(o.String()); err != nil || scanned != o {
		t.Errorf("scan %v %v", scanned, err)
	}
	defer func() {
		if recover() == nil {
			t.Error("a kind with a bad prefix did not panic")
		}
	}()
	id.New[badKind](ids)
}
