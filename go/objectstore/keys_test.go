package objectstore_test

import (
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/objectstore"
)

const keysVector = "objectstore/keys.json"

func TestKeyVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Cases []struct {
			Key    string `json:"key"`
			Repeat string `json:"repeat"`
			Length int    `json:"length"`
			Valid  bool   `json:"valid"`
		} `json:"cases"`
	}
	conformance.ReadJSON(t, keysVector, &doc)
	if len(doc.Cases) == 0 {
		t.Fatalf("%s: no cases", keysVector)
	}
	for _, c := range doc.Cases {
		key := c.Key
		if c.Repeat != "" {
			key = strings.Repeat(c.Repeat, c.Length/len(c.Repeat)+1)[:c.Length]
		}
		err := objectstore.CheckKey(key)
		if (err == nil) != c.Valid {
			t.Errorf("%s: key %q (%d chars): want valid=%v, got error %v",
				conformance.Where(keysVector, c.Key, "valid"), shorten(key), len(key), c.Valid, err)
		}
	}
	t.Logf("%d cases", len(doc.Cases))
}

func shorten(s string) string {
	if len(s) > 40 {
		return s[:40] + "..."
	}
	return s
}
