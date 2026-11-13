<!-- types: Look, Flash, FlashKind, Views, Assets -->
# Go: pages with your own kit

An organisation that builds several applications on `go/ui` may want its own look: its own components and its
own theme, kept in its own repository and changed freely, while the page plumbing stays a dependency. This page
says when that is worth doing and how. Read [server-rendered pages (templ)](ui-templ.md) first.

## Extra stylesheets, or your own kit

Keep the module's kit and add a stylesheet when you only want to change colours, fonts or spacing:

```go
a, err := assets.Load(assets.WithFiles(appFiles, "brand.css"))
v, err := views.New(a, views.WithStylesheets("brand.css")) // linked after kit.css
```

The stylesheet is linked after `kit.css`, so its rules win at equal specificity. Everything else stays the
module's, and the module's kit fixes reach you.

Keep your own copy of the kit when you want to change the markup of the components, the shell, the bare page
wrapper or the flash messages, or want to stop following the module's stylesheet. It is a fork: you maintain it.

## Steps

1. Copy the directory `go/ui/kit` into your module, for example as `yourmodule/kit`, **without the `_test.go`
   files** (the tests read the module's fixtures and its `ui-kit` directory). Copy the generated `_templ.go` files
   too, or run `templ generate` on the `.templ` files. The copy imports the module's `i18n`, `assets`, `md` and
   `look` packages; it must not import the module's `kit`.
2. Copy `ui-kit/theme` and build your stylesheet with the Tailwind standalone CLI as `ui-kit/build.sh` does (the
   kit's templ sources are the `@source`). Name it as you like, for example `own.css`.
3. Serve your stylesheet and, if you changed it, your icon sprite with `assets.WithFiles`. An added file cannot
   have the name of a built-in one, so your stylesheet is not called `kit.css`.
4. In the copy's `look.go`, change `Stylesheet()` to return your stylesheet's name, and change what else you want
   (`Main` in `look.templ` is the wrapper of a page without a shell).
5. Pass your kit to the views: `views.WithLook(yourkit.Look{})`. Give your own shell with
   `views.WithShellComponent(fn)`; `fn` returns, for a request, a component whose children are the flash stack and
   the page. When `fn` returns nil for a request (a sign-in page, say), that document has no shell and uses
   the look's `Main`.
6. Write flash messages with the shared types, `look.Flash` and `look.FlashKind`, so that your code never has to
   import the module's `kit`.

This is what the check `make -C go ui-copy-check` does with a throwaway module, and its test is the example. The
application uses only its own kit:

```go
a, err := assets.Load(assets.WithFiles(fstest.MapFS{"own.css": {Data: []byte("/* own */")}}, "own.css"))
v, err := views.New(a,
	views.WithLook(ownkit.Look{}),
	views.WithShellComponent(ownShell),
	views.WithFlashes(map[string]look.Flash{"saved": {Kind: look.FlashSuccess, Summary: "Saved."}}))
```

The check copies every non-test `.go` file of `go/ui/kit` unchanged, changes the stylesheet name to `own.css` and
the bare wrapper's class to `own-main`, and then asserts that the document links `own.css` and not `kit.css`,
uses `own-main`, renders a flash from a cookie and one sent with `Patches.Flash` through the copy, and puts the
page inside the shell component. It also asserts that no file of the throwaway module imports the module's `kit`.

## What the copy must keep

The kit's components come from the Topcoat UI components and the icons from Lucide. A copy of the kit, and of
`theme`, keeps their notices: `ui-kit/THIRD-PARTY-NOTICES.md` and the licence files under `ui-kit/icons`. Copy
them with it.

## What you give up

- The module's kit fixes and new components do not reach the copy. You merge them by hand when you want them.
- The conformance fixtures (`ui-kit/fixtures`) describe the module's kit, not your copy.

## What stays a dependency

The document skeleton (doctype, `<html lang>`, the head, Datastar and the island scripts, the session-expired
listener), the page policy and the document headers, the flash cookie and `SetFlash`/`SeeOther`, the `fw-page`
wrapper and the patches (`Patches`), and the packages `ds`, `assets`, `md`, `i18n`, `compress` and `fwui`. The
contract is [`server-rendered-ui.md`](../../contracts/server-rendered-ui.md) section 5 rule 7 (Go only; the Java
module has no such options).
