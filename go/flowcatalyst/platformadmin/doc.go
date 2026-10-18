// Package platformadmin holds the admin sections that show what the FlowCatalyst platform knows
// about the application (admin contract sections 11 and 12): event types and roles ([Catalog]), users
// and invitations ([Users]) and events and dispatch jobs ([Activity]). Each section adds itself to
// the registry of package admin at declaration time and is bound in the build function with the
// application's [flowcatalyst.PlatformClient].
//
// The platform is called with the application's own service account, after the operation gate has
// checked the signed-in user's admin permission; a refused user never reaches the platform. The
// framework confines every answer to the application's own records, whatever the platform sends, and
// turns a platform failure into 502 PLATFORM_ERROR with a message that names no host, token or
// platform text (the cause goes to the log); a disabled FlowCatalyst module answers 503
// PLATFORM_NOT_CONFIGURED.
//
//	adm := admin.Declare(web, app)
//	catalog := platformadmin.DeclareCatalog(adm)
//	users := platformadmin.DeclareUsers(adm)
//	activity := platformadmin.DeclareActivity(adm)
//	...
//	routes, err := platformadmin.Build(b, client, catalog, users, activity)
package platformadmin
