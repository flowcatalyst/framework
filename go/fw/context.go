package fw

import (
	"errors"
	"log/slog"
	"slices"
	"strings"
)

// Context says who is acting, for which client, and which request or message the work belongs to
// (core contract section 3). It is a value passed explicitly as a parameter beside the standard
// context.Context (by convention named cx), never stored in a context.Context or a package
// variable. Every Context has a client and a correlation id; the zero Context has neither and is
// refused by every framework API.
type Context struct {
	client      ClientID
	principal   PrincipalID
	kind        PrincipalKind
	application ApplicationCode
	correlation CorrelationID
	causation   CausationID
	execution   ExecutionID
	permissions []Permission
}

// ErrZeroContext is returned when a framework API is given the zero Context.
var ErrZeroContext = errors.New("fw: the zero Context has no client")

// NewContext returns a Context for a client and a correlation id, both required.
func NewContext(client ClientID, correlation CorrelationID) (Context, error) {
	if client.IsZero() {
		return Context{}, errors.New("fw: a Context needs a client")
	}
	if correlation.IsZero() {
		return Context{}, errors.New("fw: a Context needs a correlation id")
	}
	return Context{client: client, correlation: correlation}, nil
}

// ContextFor returns a Context for a client with a new random correlation id, as the Java
// framework's Context.of does. It panics on the zero client (a programming error).
func ContextFor(client ClientID) Context {
	cx, err := NewContext(client, NewCorrelationID())
	if err != nil {
		panic(err)
	}
	return cx
}

// IsZero reports whether cx is the zero Context.
func (cx Context) IsZero() bool { return cx.client.IsZero() }

// Client returns the client; never the zero value for a Context made by this package.
func (cx Context) Client() ClientID { return cx.client }

// Correlation returns the correlation id.
func (cx Context) Correlation() CorrelationID { return cx.correlation }

// Principal returns the acting principal, if any.
func (cx Context) Principal() (PrincipalID, bool) { return cx.principal, !cx.principal.IsZero() }

// Application returns the application code, if any.
func (cx Context) Application() (ApplicationCode, bool) {
	return cx.application, !cx.application.IsZero()
}

// Causation returns the id of what caused this work, if any.
func (cx Context) Causation() (CausationID, bool) { return cx.causation, !cx.causation.IsZero() }

// Execution returns the id of the job or run this work belongs to, if any.
func (cx Context) Execution() (ExecutionID, bool) { return cx.execution, !cx.execution.IsZero() }

// Permissions returns a copy of the permissions the principal holds.
func (cx Context) Permissions() []Permission { return slices.Clone(cx.permissions) }

// PrincipalKind is what kind of principal is acting (core contract section 3): a person or a
// service account, the platform's type claim. It is not written to the outbox; it is for
// Authorize and logs.
type PrincipalKind int

const (
	// PrincipalUser is a person.
	PrincipalUser PrincipalKind = iota + 1
	// PrincipalService is a service account (the FlowCatalyst receivers run as one).
	PrincipalService
)

// String returns USER or SERVICE.
func (k PrincipalKind) String() string {
	switch k {
	case PrincipalUser:
		return "USER"
	case PrincipalService:
		return "SERVICE"
	}
	return ""
}

// ForClient returns a copy for another client that keeps everything else: principal, kind,
// application, correlation, causation and execution ids and permissions (core contract section
// 3a). It does not check that the principal may act for that client; that is the use case's
// Authorize, as for any caller. The receiver is unchanged. It panics on the zero ClientID or on
// the zero Context.
func (cx Context) ForClient(c ClientID) Context {
	if c.IsZero() {
		panic("fw: Context.ForClient with the zero ClientID")
	}
	if cx.IsZero() {
		panic("fw: Context.ForClient on the zero Context")
	}
	cx.client = c
	cx.permissions = slices.Clone(cx.permissions)
	return cx
}

// WithPrincipal returns a copy with the principal set and its kind unknown (cleared, so a kind
// never describes another principal than the one it came with). It panics on the zero
// PrincipalID.
func (cx Context) WithPrincipal(p PrincipalID) Context {
	if p.IsZero() {
		panic("fw: Context.WithPrincipal with the zero PrincipalID")
	}
	cx.principal = p
	cx.kind = 0
	return cx
}

// WithPrincipalOfKind returns a copy with the principal and its kind set. It panics on the zero
// PrincipalID or an unknown kind.
func (cx Context) WithPrincipalOfKind(p PrincipalID, k PrincipalKind) Context {
	if k != PrincipalUser && k != PrincipalService {
		panic("fw: Context.WithPrincipalOfKind with an unknown PrincipalKind")
	}
	cx = cx.WithPrincipal(p)
	cx.kind = k
	return cx
}

// PrincipalKind returns the principal's kind, when it is known.
func (cx Context) PrincipalKind() (PrincipalKind, bool) { return cx.kind, cx.kind != 0 }

// WithApplication returns a copy with the application set. It panics on the zero value.
func (cx Context) WithApplication(a ApplicationCode) Context {
	if a.IsZero() {
		panic("fw: Context.WithApplication with the zero ApplicationCode")
	}
	cx.application = a
	return cx
}

// WithCorrelation returns a copy with another correlation id. It panics on the zero value.
func (cx Context) WithCorrelation(c CorrelationID) Context {
	if c.IsZero() {
		panic("fw: Context.WithCorrelation with the zero CorrelationID")
	}
	cx.correlation = c
	return cx
}

// WithCausation returns a copy with the causation id set. It panics on the zero value.
func (cx Context) WithCausation(c CausationID) Context {
	if c.IsZero() {
		panic("fw: Context.WithCausation with the zero CausationID")
	}
	cx.causation = c
	return cx
}

// WithExecution returns a copy with the execution id set. It panics on the zero value.
func (cx Context) WithExecution(e ExecutionID) Context {
	if e.IsZero() {
		panic("fw: Context.WithExecution with the zero ExecutionID")
	}
	cx.execution = e
	return cx
}

// WithPermissions returns a copy holding exactly these permissions (duplicates removed). It
// panics on a zero Permission.
func (cx Context) WithPermissions(ps ...Permission) Context {
	out := make([]Permission, 0, len(ps))
	for _, p := range ps {
		if p.IsZero() {
			panic("fw: Context.WithPermissions with the zero Permission")
		}
		if !slices.Contains(out, p) {
			out = append(out, p)
		}
	}
	cx.permissions = out
	return cx
}

// HasPermission reports whether some held permission matches the required one: both have the
// same number of ':'-separated segments and each held segment is '*' or equal to the required
// segment (the platform's rule). The zero Permission never matches.
func (cx Context) HasPermission(required Permission) bool {
	if required.IsZero() {
		return false
	}
	wanted := strings.Split(required.v, ":")
	for _, held := range cx.permissions {
		if permissionMatches(strings.Split(held.v, ":"), wanted) {
			return true
		}
	}
	return false
}

func permissionMatches(held, wanted []string) bool {
	if len(held) != len(wanted) {
		return false
	}
	for i := range held {
		if held[i] != "*" && held[i] != wanted[i] {
			return false
		}
	}
	return true
}

// LogAttrs returns the Context's fields as slog attributes: client, correlation id, and the
// principal, application, causation and execution ids when present.
func (cx Context) LogAttrs() []slog.Attr {
	attrs := []slog.Attr{
		slog.String("client", cx.client.v),
		slog.String("correlation_id", cx.correlation.v),
	}
	if !cx.principal.IsZero() {
		attrs = append(attrs, slog.String("principal", cx.principal.v))
	}
	if cx.kind != 0 {
		attrs = append(attrs, slog.String("principal_kind", cx.kind.String()))
	}
	if !cx.application.IsZero() {
		attrs = append(attrs, slog.String("application", cx.application.v))
	}
	if !cx.causation.IsZero() {
		attrs = append(attrs, slog.String("causation_id", cx.causation.v))
	}
	if !cx.execution.IsZero() {
		attrs = append(attrs, slog.String("execution_id", cx.execution.v))
	}
	return attrs
}
