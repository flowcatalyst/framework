package secrets

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

const nameMax = 100

var (
	storePattern = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)
	namePattern  = regexp.MustCompile(`^[a-z0-9]+(-[a-z0-9]+)*$`)
)

// Ref names a secret: a store (empty for the default store) and a name.
type Ref struct {
	store string
	name  string
}

// NewRef checks a store name ([a-z][a-z0-9-]*, at most 100 characters, "" for the default store)
// and a secret name ([a-z0-9]+(-[a-z0-9]+)*, at most 100 characters: no separators, no dots, no
// traversal).
func NewRef(store, name string) (Ref, error) {
	if store != "" && (len(store) > 100 || !storePattern.MatchString(store)) {
		return Ref{}, fmt.Errorf("secret store name must match [a-z][a-z0-9-]*")
	}
	if err := checkName(name); err != nil {
		return Ref{}, err
	}
	return Ref{store: store, name: name}, nil
}

func checkName(name string) error {
	if len(name) > nameMax || !namePattern.MatchString(name) {
		return fmt.Errorf("secret name must match [a-z0-9]+(-[a-z0-9]+)*, at most %d characters", nameMax)
	}
	return nil
}

// ParseRef parses "name" or "store:name". The text is not echoed when it is over 200 characters.
func ParseRef(text string) (Ref, error) {
	store, name := "", text
	var err error
	if before, after, found := strings.Cut(text, ":"); found {
		store, name = before, after
		if store == "" {
			err = fmt.Errorf("secret store name must match [a-z][a-z0-9-]*")
		}
	}
	var r Ref
	if err == nil {
		r, err = NewRef(store, name)
	}
	if err != nil {
		shown := "'" + text + "'"
		if len(text) > 200 {
			shown = "(" + strconv.Itoa(len(text)) + " characters)"
		}
		return Ref{}, fmt.Errorf("not a secret reference %s: %w", shown, err)
	}
	return r, nil
}

// MustParseRef is [ParseRef] for literals; it panics on a bad reference.
func MustParseRef(text string) Ref {
	r, err := ParseRef(text)
	if err != nil {
		panic(err)
	}
	return r
}

// Store returns the store name, and false for the default store.
func (r Ref) Store() (string, bool) { return r.store, r.store != "" }

// Name returns the secret's name.
func (r Ref) Name() string { return r.name }

// IsZero reports whether r is the zero Ref, which is invalid.
func (r Ref) IsZero() bool { return r.name == "" }

// String returns "name" or "store:name".
func (r Ref) String() string {
	if r.store == "" {
		return r.name
	}
	return r.store + ":" + r.name
}
