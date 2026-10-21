// Package objectstore keeps named byte values by key (object-stores contract): put, get and
// delete, nothing else, so every driver can honour it the same way. The drivers are the memory
// driver and the folder driver here and the S3 driver in objectstore/s3store (compiled only when
// imported). Stores are named in code and built by [Build], like lock stores.
//
//	d := objectstore.Declare(app)                                     // in main
//	all, err := objectstore.Build(ctx, b, d,                          // in build
//		objectstore.WithStore("archive", s3store.Bucket("acme-archive", s3store.Prefix("ingest/sets"))),
//		objectstore.WithStore("scratch", objectstore.Folder("/var/tmp/archive")), // local and test only
//		objectstore.DefaultStore("archive"))
//	archive, _ := all.Default()
//	err = archive.Put(ctx, "sets/2026-10-21/apply-1.json", data)
package objectstore

import (
	"context"
	"errors"
	"fmt"
)

// MaxKeyLength is the longest key, in characters.
const MaxKeyLength = 512

// DefaultMaxBytes is the largest value a store accepts unless told otherwise: 64 MiB.
const DefaultMaxBytes = 64 * 1024 * 1024

// ErrInvalidArgument is returned (wrapped) for a key that is not allowed and for a value over the
// store's limit; nothing is stored.
var ErrInvalidArgument = errors.New("invalid object store argument")

// ErrStore wraps every input/output or service failure of a store, and a stored value found over
// the limit. The message names the store and key. The framework does not retry.
var ErrStore = errors.New("object store failure")

// Store is the driver contract: three operations on keys that [CheckKey] accepts. Safe for
// concurrent use. Returned and stored slices are copies.
type Store interface {
	// Name returns the name the store was given in [WithStore].
	Name() string
	// Put stores content under key, replacing any earlier value completely and atomically for
	// readers (the old value or the new one, never a mixture). Empty content is a value.
	Put(ctx context.Context, key string, content []byte) error
	// Get returns the value, and false (with a nil error) when there is none.
	Get(ctx context.Context, key string) ([]byte, bool, error)
	// Delete removes the value; removing an absent value is not an error.
	Delete(ctx context.Context, key string) error
}

// CheckKey checks a key: 1 to [MaxKeyLength] characters, segments separated by a single '/', each
// segment starting with a letter or digit and holding only ASCII letters, digits, '.', '-' and
// '_'. So no leading or trailing slash, no empty segment, and no "." or ".." (which is what keeps
// a key inside a folder store). The error wraps [ErrInvalidArgument].
func CheckKey(key string) error {
	if key == "" || len(key) > MaxKeyLength {
		return fmt.Errorf("%w: key must be 1 to %d characters, was %d", ErrInvalidArgument, MaxKeyLength, len(key))
	}
	start := true // at the start of a segment
	for i := 0; i < len(key); i++ {
		c := key[i]
		alnum := c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
		switch {
		case c == '/' && !start:
			start = true
		case alnum:
			start = false
		case !start && (c == '.' || c == '-' || c == '_'):
		default:
			return fmt.Errorf("%w: key '%s' has an invalid segment; segments are letters, digits, '.', '-' and '_', starting with a letter or digit, separated by '/'",
				ErrInvalidArgument, printable(key))
		}
	}
	if start {
		return fmt.Errorf("%w: key '%s' ends with '/'", ErrInvalidArgument, printable(key))
	}
	return nil
}

// printable shortens a key for a message and replaces what is not printable ASCII.
func printable(key string) string {
	b := make([]byte, 0, 80)
	for i := 0; i < len(key) && i < 80; i++ {
		c := key[i]
		if c < 32 || c >= 127 {
			c = '?'
		}
		b = append(b, c)
	}
	return string(b)
}

// CheckSize returns an error wrapping [ErrInvalidArgument] when n bytes are over the limit. Drivers
// use it before storing.
func CheckSize(n, maxBytes int) error {
	if n > maxBytes {
		return fmt.Errorf("%w: content is %d bytes, over the store's limit of %d", ErrInvalidArgument, n, maxBytes)
	}
	return nil
}
