// Package admin is the skeleton of an application's admin area (admin contract,
// docs/contracts/admin.md): a registry of sections, each with the permission that shows it and the
// typed operations it serves; the operation gate that refuses a caller without the permission
// before anything is read; the navigation, which lists only the sections the caller may use; the
// about operation; and the config section, which shows the settings the application runs with and
// never a secret.
//
// An application declares the skeleton at declaration time, next to its other operations, lets
// other packages add sections to the same registry, and builds the routes in its build function:
//
//	web := httpfw.Declare(app)
//	adm := admin.Declare(web, fw.MustApplicationCode("orders-demo"))
//	adm.Config()                                  // the config section (optional)
//	adm.Section(ordersSection)                    // the application's own section
//	...
//	routes, err := adm.Build(b)                   // build: checks the whole registry, binds the handlers
//
// The data sections that need the FlowCatalyst platform (event types, roles, users, invitations,
// events and dispatch jobs) are in package platformadmin of the flowcatalyst module and add
// themselves to the same registry.
package admin
