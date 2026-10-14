// Package flowcatalyst will connect an application built on the Go framework to the FlowCatalyst
// platform: the platform client as the application's service account, definition sync, the
// flowcatalyst and outbox health checks, and the dispatch-job and scheduled-job receivers
// (docs/contracts/flowcatalyst.md, docs/go/capabilities.md section 11).
//
// It is its own module, github.com/flowcatalyst/framework/go/flowcatalyst, because it is the only
// part of the Go framework that imports the platform SDK (fcsdk): an application that never uses
// FlowCatalyst does not get fcsdk in its module graph. Nothing is built yet; it arrives with
// milestone G4 (docs/go/milestones.md).
package flowcatalyst
