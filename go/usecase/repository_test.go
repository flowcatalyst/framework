package usecase_test

import (
	"fmt"
	"sync/atomic"
	"testing"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/usecase/usecasetest"
)

func TestThingRepositoryKeepsTheContract(t *testing.T) {
	t.Parallel()
	db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	var n atomic.Int64
	usecasetest.Repository(t, usecasetest.Harness[thing, string]{
		Repo: thingRepo{},
		DB:   db,
		New: func() (thing, string) {
			id := fmt.Sprintf("thg_%d", n.Add(1))
			return thing{id: id, name: "name " + id}, id
		},
		Change: func(x thing) thing { x.name += " changed"; return x },
		Equal:  func(a, b thing) bool { return a.id == b.id && a.name == b.name },
	})
}
