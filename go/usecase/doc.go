// Package usecase is the framework's write path (write-path contract): use cases with validate,
// authorize and execute phases, a runner that applies each plan in one transaction with
// optimistic locking, client scoping and the contract's failure table, and the outbox writer
// whose event, dispatch-job and audit rows are byte for byte the Java framework's.
//
//	type AddLineUseCase struct{ ... }                    // Name, Validate, Authorize, Execute
//	addLine, err := usecase.Define(runner, AddLineUseCase{...}, usecase.WithConflictPolicy(retry))
//	done, err := addLine.Run(ctx, cx, cmd)              // err is always a *usecase.Error
//
// A use case is an interface with all four phases, so one without Authorize does not compile;
// Execute receives only an Authorized command, which only the runner makes. The aggregate's
// persistence state is the framework's: Fresh (inserted at version 1 for the caller's client) or
// Loaded (updated or deleted where client and version still match).
package usecase
