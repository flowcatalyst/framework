// Package partitionsadmin is the partitions section of the application admin (partitions contract
// section 11, admin contract section 3a): a read of the state of every declared partitioned table,
// its partitions and its archives, and two operator actions (run a pass now, create the partitions
// of a range). It is headless: typed operations, permissions and a navigation entry, no page; a
// server-rendered page or a SPA draws it.
//
// The section is for platform operators only. A partition holds the rows of every client, so besides
// {app}:admin:partitions:view (reads) or :manage (the two actions) a caller must be a platform
// operator (tier ANCHOR); a PARTNER, a CLIENT and a caller with no tier are refused with 403
// FORBIDDEN, and the navigation does not list the section for them. Never grant these permissions to
// a role that client administrators may assign (a client-managed role): with the FlowCatalyst module,
// start-up refuses it.
//
//	adm := admin.Declare(web, app)
//	pa := partitionsadmin.Declare(adm)           // declaration time: the section and its operations
//	...
//	ps, err := partitions.Build(ctx, b, pd, dbs, opts...)
//	routes, err := pa.Build(ps)                  // in build, after partitions.Build
//
// Deliberately not provided: dropping or detaching a partition by hand, changing a table's retention
// (a fact of the code) and restoring an archive.
package partitionsadmin
