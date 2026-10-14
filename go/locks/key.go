package locks

import (
	"fmt"

	"github.com/flowcatalyst/framework/go/fw"
)

// Key is a lock key: a scope (service or client) and a name matching [A-Za-z0-9._:-]{1,200}
// (locks contract section 1). Stored forms: svc:<name> and client:<clientId>:<name>; a client id
// never contains ':', so keys of different scopes and clients never collide.
type Key struct {
	client fw.ClientID
	name   string
	scoped bool // true for a client-scoped key
}

// ServiceKey returns a service-scoped key.
func ServiceKey(name string) (Key, error) {
	if err := checkName(name); err != nil {
		return Key{}, err
	}
	return Key{name: name}, nil
}

// ClientKey returns a key scoped to one client.
func ClientKey(client fw.ClientID, name string) (Key, error) {
	if client.IsZero() {
		return Key{}, fmt.Errorf("%w: a client-scoped lock key needs a client", ErrInvalidArgument)
	}
	if err := checkName(name); err != nil {
		return Key{}, err
	}
	return Key{client: client, name: name, scoped: true}, nil
}

// MustServiceKey is [ServiceKey] for literals; it panics on a bad name.
func MustServiceKey(name string) Key {
	k, err := ServiceKey(name)
	if err != nil {
		panic(err)
	}
	return k
}

// Stored returns the key's stored form.
func (k Key) Stored() string {
	if k.scoped {
		return "client:" + k.client.String() + ":" + k.name
	}
	return "svc:" + k.name
}

// String returns the stored form.
func (k Key) String() string { return k.Stored() }

// Name returns the key's name.
func (k Key) Name() string { return k.name }

// Client returns the client of a client-scoped key.
func (k Key) Client() (fw.ClientID, bool) { return k.client, k.scoped }

// IsZero reports whether k is the zero Key, which no operation accepts.
func (k Key) IsZero() bool { return k.name == "" }

func checkName(name string) error {
	if len(name) < 1 || len(name) > 200 {
		return fmt.Errorf("%w: lock name must match [A-Za-z0-9._:-]{1,200}: %q", ErrInvalidArgument, name)
	}
	for i := range len(name) {
		c := name[i]
		ok := (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
			c == '.' || c == '_' || c == ':' || c == '-'
		if !ok {
			return fmt.Errorf("%w: lock name must match [A-Za-z0-9._:-]{1,200}: %q", ErrInvalidArgument, name)
		}
	}
	return nil
}
