// Package id gives aggregates typed ids: id.ID[OrderKind] and id.ID[LineKind] are different types,
// so an order id cannot be passed where a line id is expected. An id is "<prefix>_<TSID>"
// (ord_0ABCDEFGHJKMN), the prefix coming from the kind:
//
//	type OrderKind struct{}
//	func (OrderKind) Prefix() string { return "ord" }
//	type OrderID = id.ID[OrderKind]
//
//	orderID := id.New[OrderKind](b.Ids())
//
// IDs marshal as their text (JSON, sqlc parameters through driver.Valuer, scans through
// sql.Scanner); the zero ID is refused when stored.
package id

import (
	"database/sql/driver"
	"errors"
	"fmt"
	"strings"

	"github.com/flowcatalyst/framework/go/fw"
)

// Kind names the prefix of a kind of id. Implement it on an empty struct type.
type Kind interface {
	Prefix() string
}

// ID is a typed id.
type ID[K Kind] struct{ v string }

// ErrInvalid wraps every malformed id.
var ErrInvalid = errors.New("invalid id")

func prefixOf[K Kind]() string {
	var k K
	return k.Prefix()
}

// New returns a new id of kind K. It panics when K's prefix is not [a-z][a-z0-9]* (a
// programming error the first test finds).
func New[K Kind](ids *fw.Ids) ID[K] {
	p, err := fw.ParseIDPrefix(prefixOf[K]())
	if err != nil {
		panic(fmt.Sprintf("id: the prefix of %T: %v", *new(K), err))
	}
	return ID[K]{v: ids.Prefixed(p)}
}

// Parse checks s: K's prefix, '_', and a canonical 13-character TSID.
func Parse[K Kind](s string) (ID[K], error) {
	p := prefixOf[K]()
	tail, ok := strings.CutPrefix(s, p+"_")
	if !ok || !fw.CanonicalTSID(tail) {
		return ID[K]{}, fmt.Errorf("%w: %q is not %s_ followed by a TSID", ErrInvalid, s, p)
	}
	return ID[K]{v: s}, nil
}

// Must is [Parse] for literals; it panics on a malformed id.
func Must[K Kind](s string) ID[K] {
	x, err := Parse[K](s)
	if err != nil {
		panic(err)
	}
	return x
}

// String returns the id's text.
func (x ID[K]) String() string { return x.v }

// IsZero reports whether x is the zero ID.
func (x ID[K]) IsZero() bool { return x.v == "" }

// MarshalText returns the id's text; the zero ID is an error.
func (x ID[K]) MarshalText() ([]byte, error) {
	if x.IsZero() {
		return nil, fmt.Errorf("%w: the zero %s id", ErrInvalid, prefixOf[K]())
	}
	return []byte(x.v), nil
}

// UnmarshalText parses the id's text.
func (x *ID[K]) UnmarshalText(b []byte) error {
	v, err := Parse[K](string(b))
	if err != nil {
		return err
	}
	*x = v
	return nil
}

// Value implements driver.Valuer: the id's text; the zero ID is an error, so it is never stored.
func (x ID[K]) Value() (driver.Value, error) {
	if x.IsZero() {
		return nil, fmt.Errorf("%w: the zero %s id cannot be stored", ErrInvalid, prefixOf[K]())
	}
	return x.v, nil
}

// Scan implements sql.Scanner for text columns; a malformed value is an error.
func (x *ID[K]) Scan(src any) error {
	switch v := src.(type) {
	case string:
		return x.UnmarshalText([]byte(v))
	case []byte:
		return x.UnmarshalText(v)
	}
	return fmt.Errorf("%w: cannot scan %T into a %s id", ErrInvalid, src, prefixOf[K]())
}
