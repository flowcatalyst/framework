package fw

import (
	"crypto/rand"
	"errors"
	"fmt"
	"strconv"
	"strings"
)

// The value types of the core contract's Context (section 3). Each wraps a checked string, is
// built by Parse... (input) or Must... (literals in source, panicking on a bad value), and has
// IsZero: the zero value of every one of them is refused by every framework API that takes one.

// ErrInvalidID is wrapped by every Parse... function of this file.
var ErrInvalidID = errors.New("invalid id")

// ClientID identifies the client (tenant) a piece of work is for. It matches
// [A-Za-z0-9._-]{1,100}, so it never contains ':' and can be part of a composite key.
type ClientID struct{ v string }

// ParseClientID checks s against [A-Za-z0-9._-]{1,100}.
func ParseClientID(s string) (ClientID, error) {
	if len(s) < 1 || len(s) > 100 {
		return ClientID{}, invalid("ClientId must match [A-Za-z0-9._-]{1,100}", s)
	}
	for i := range len(s) {
		c := s[i]
		if !isAlnum(c) && c != '.' && c != '_' && c != '-' {
			return ClientID{}, invalid("ClientId must match [A-Za-z0-9._-]{1,100}", s)
		}
	}
	return ClientID{v: s}, nil
}

// MustClientID is [ParseClientID] for literals; it panics on a bad value.
func MustClientID(s string) ClientID { return must(ParseClientID(s)) }

// String returns the id.
func (id ClientID) String() string { return id.v }

// IsZero reports whether id is the zero value.
func (id ClientID) IsZero() bool { return id.v == "" }

// PrincipalID identifies who is acting: a user or a service account. Not blank.
type PrincipalID struct{ v string }

// ParsePrincipalID checks that s is not blank.
func ParsePrincipalID(s string) (PrincipalID, error) {
	if strings.TrimSpace(s) == "" {
		return PrincipalID{}, invalid("PrincipalId must not be blank", s)
	}
	return PrincipalID{v: s}, nil
}

// MustPrincipalID is [ParsePrincipalID] for literals.
func MustPrincipalID(s string) PrincipalID { return must(ParsePrincipalID(s)) }

// String returns the id.
func (id PrincipalID) String() string { return id.v }

// IsZero reports whether id is the zero value.
func (id PrincipalID) IsZero() bool { return id.v == "" }

// ApplicationCode is an application's code, matching [a-z][a-z0-9-]*.
type ApplicationCode struct{ v string }

// ParseApplicationCode checks s against [a-z][a-z0-9-]*.
func ParseApplicationCode(s string) (ApplicationCode, error) {
	if !isSlug(s) {
		return ApplicationCode{}, invalid("ApplicationCode must match [a-z][a-z0-9-]*", s)
	}
	return ApplicationCode{v: s}, nil
}

// MustApplicationCode is [ParseApplicationCode] for literals.
func MustApplicationCode(s string) ApplicationCode { return must(ParseApplicationCode(s)) }

// String returns the code.
func (c ApplicationCode) String() string { return c.v }

// IsZero reports whether c is the zero value.
func (c ApplicationCode) IsZero() bool { return c.v == "" }

// CorrelationID ties together the work done for one request or message. Not blank.
type CorrelationID struct{ v string }

// ParseCorrelationID checks that s is not blank.
func ParseCorrelationID(s string) (CorrelationID, error) {
	if strings.TrimSpace(s) == "" {
		return CorrelationID{}, invalid("CorrelationId must not be blank", s)
	}
	return CorrelationID{v: s}, nil
}

// MustCorrelationID is [ParseCorrelationID] for literals.
func MustCorrelationID(s string) CorrelationID { return must(ParseCorrelationID(s)) }

// NewCorrelationID returns a random correlation id (a version-4 UUID), as the Java framework
// generates one when a request carries none.
func NewCorrelationID() CorrelationID {
	var b [16]byte
	_, _ = rand.Read(b[:]) // crypto/rand.Read never fails (Go 1.24+)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return CorrelationID{v: fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])}
}

// String returns the id.
func (id CorrelationID) String() string { return id.v }

// IsZero reports whether id is the zero value.
func (id CorrelationID) IsZero() bool { return id.v == "" }

// CausationID names the event or message that caused this work. Not blank.
type CausationID struct{ v string }

// ParseCausationID checks that s is not blank.
func ParseCausationID(s string) (CausationID, error) {
	if strings.TrimSpace(s) == "" {
		return CausationID{}, invalid("causation id must not be blank", s)
	}
	return CausationID{v: s}, nil
}

// MustCausationID is [ParseCausationID] for literals.
func MustCausationID(s string) CausationID { return must(ParseCausationID(s)) }

// String returns the id.
func (id CausationID) String() string { return id.v }

// IsZero reports whether id is the zero value.
func (id CausationID) IsZero() bool { return id.v == "" }

// ExecutionID names the job or run this work belongs to. Not blank.
type ExecutionID struct{ v string }

// ParseExecutionID checks that s is not blank.
func ParseExecutionID(s string) (ExecutionID, error) {
	if strings.TrimSpace(s) == "" {
		return ExecutionID{}, invalid("execution id must not be blank", s)
	}
	return ExecutionID{v: s}, nil
}

// MustExecutionID is [ParseExecutionID] for literals.
func MustExecutionID(s string) ExecutionID { return must(ParseExecutionID(s)) }

// String returns the id.
func (id ExecutionID) String() string { return id.v }

// IsZero reports whether id is the zero value.
func (id ExecutionID) IsZero() bool { return id.v == "" }

// Permission is a permission string: segments separated by ':', each not empty, for example
// "orders:orders:write". A held permission may use '*' for a segment ([Context.HasPermission]).
type Permission struct{ v string }

// ParsePermission checks that s is not blank and has no empty segment.
func ParsePermission(s string) (Permission, error) {
	if strings.TrimSpace(s) == "" {
		return Permission{}, invalid("permission must not be blank", s)
	}
	for seg := range strings.SplitSeq(s, ":") {
		if seg == "" || strings.TrimSpace(seg) != seg {
			return Permission{}, invalid("permission segments must not be empty or padded", s)
		}
	}
	return Permission{v: s}, nil
}

// MustPermission is [ParsePermission] for literals.
func MustPermission(s string) Permission { return must(ParsePermission(s)) }

// String returns the permission.
func (p Permission) String() string { return p.v }

// IsZero reports whether p is the zero value.
func (p Permission) IsZero() bool { return p.v == "" }

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

func invalid(rule, got string) error {
	return fmt.Errorf("%w: %s: %s", ErrInvalidID, rule, quote(got))
}

func quote(s string) string { return strconv.Quote(s) }

func isAlnum(c byte) bool {
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
}

// isSlug reports whether s matches [a-z][a-z0-9-]*.
func isSlug(s string) bool {
	if s == "" || s[0] < 'a' || s[0] > 'z' {
		return false
	}
	for i := 1; i < len(s); i++ {
		c := s[i]
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' {
			return false
		}
	}
	return true
}
