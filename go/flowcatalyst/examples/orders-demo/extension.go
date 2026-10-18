package main

import (
	"context"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/flowcatalyst/platformadmin"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// extension adds the platform-backed admin sections to the orders demo's admin area: event types
// and roles, users and invitations, events and dispatch jobs, through the application's service
// account, and the admin permissions and role in the definition sync (the Java OrdersWeb listing
// FlowCatalystModule, PlatformAdminModule, PlatformUsersAdminModule and PlatformActivityAdminModule).
type extension struct {
	fcd   *flowcatalyst.Declared
	parts []platformadmin.Part
}

// Declare implements ordersweb.Extension.
func (e *extension) Declare(app *fw.App, _ *httpfw.Declared, adm *admin.Declared) {
	e.fcd = flowcatalyst.Declare(app)
	e.parts = []platformadmin.Part{platformadmin.DeclareCatalog(adm), platformadmin.DeclareUsers(adm), platformadmin.DeclareActivity(adm)}
}

// Finish implements ordersweb.Extension.
func (e *extension) Finish(adm *admin.Declared) { e.fcd.AdminDefinitions(adm) }

// Build implements ordersweb.Extension.
func (e *extension) Build(ctx context.Context, b *fw.Boot, dbs *database.Databases) (*httpfw.Routes, error) {
	fc, err := flowcatalyst.Build(ctx, b, e.fcd, flowcatalyst.WithDatabases(dbs))
	if err != nil {
		return nil, err
	}
	return platformadmin.Build(b, fc.Client(), e.parts...)
}

// e2eExtension is the extension for the browser launcher: the same sections against a fake platform
// the launcher starts, which the controls can make refuse its reads and writes.
type e2eExtension struct {
	extension
	platform *demoPlatform
}

func newE2EExtension() *e2eExtension { return &e2eExtension{platform: startDemoPlatform()} }

// Environment implements ordersweb.E2EHooks.
func (e *e2eExtension) Environment() map[string]string { return e.platform.Environment() }

// Reads implements ordersweb.E2EHooks.
func (e *e2eExtension) Reads(status int) { e.platform.Reads(status) }

// Reset implements ordersweb.E2EHooks.
func (e *e2eExtension) Reset() { e.platform.Reset() }

// Close implements ordersweb.E2EHooks.
func (e *e2eExtension) Close() { e.platform.Close() }
