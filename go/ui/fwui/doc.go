// Package fwui wires the server-rendered UI (packages assets, views, kit, ds) into the framework:
// a module that declares itself like the other modules ([Declare], then [Declared.Build] in the
// build function), serves the static files on httpfw routes, hands back the [views.Views] the
// handlers render with, and adds what only an application on the framework can: the shell user from
// the session ([ShellUser]) and the mapping of a use case's error to what a page shows ([Classify],
// [Module.Fail]).
//
//	ui := fwui.Declare(app)                             // declaring
//	...
//	mod, err := ui.Build(b,                             // in the build function
//		fwui.WithViews(views.WithShell(shellFor), views.WithLogin("/auth/login", "")))
//	srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{mod.Routes(), pages}, httpfw.WithResolver(...))
//	...
//	err = mod.Views().Page(w, r, "Orders", ordersPage(rows)) // in a handler
//
// Form posts: a page whose forms post natively declares those routes with httpfw.AcceptForms(), so
// the sessions CSRF check accepts a form body on them (and on no other route).
package fwui
