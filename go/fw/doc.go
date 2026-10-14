// Package fw is the core of the Go application framework: the application's lifecycle
// (declare, configure, build, run, shut down), the request-scoped [Context] and its ids, the
// [Clock], TSID generation ([Ids]), health checks with criticality and commands.
//
// An application declares what it uses with the Declare functions of each capability package,
// which return typed handles, then builds its services in one explicit function passed to
// [App.Launch]. There is no container: a missing dependency is a compile error.
//
// The rules are those of the framework's core contract (docs/contracts/core.md); where Go
// differs from the Java framework, docs/go/design.md says why.
package fw
