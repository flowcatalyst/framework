// Package types holds the fixture's column types: a generic wrapper and an alias of one of its
// instantiations, used as a sqlc go_type override.
package types

import (
	"database/sql/driver"
	"fmt"
)

// Wrapper wraps a database value of type T.
type Wrapper[T any] struct{ V T }

// Scan implements sql.Scanner.
func (w *Wrapper[T]) Scan(src any) error {
	v, ok := src.(T)
	if !ok {
		return fmt.Errorf("types: cannot scan %T into %T", src, w.V)
	}
	w.V = v
	return nil
}

// Value implements driver.Valuer.
func (w Wrapper[T]) Value() (driver.Value, error) { return w.V, nil }

// ThingID is the type of things.id: an alias of a generic instantiation.
type ThingID = Wrapper[int64]
