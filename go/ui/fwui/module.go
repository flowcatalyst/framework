package fwui

import (
	"log/slog"
	"net/http"
	"slices"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/ui/assets"
	"github.com/flowcatalyst/framework/go/ui/views"
)

// ModuleID is the UI module's id.
const ModuleID = "ui"

// Declared is the module's declaration, from [Declare].
type Declared struct{}

// Declare adds the module to the application. It declares no settings: what the UI does is code,
// set with the options of [Declared.Build].
func Declare(app *fw.App) *Declared {
	app.Module(ModuleID)
	return &Declared{}
}

// Option changes how [Declared.Build] builds the module.
type Option func(*options)

type options struct {
	assets   []assets.Option
	views    []views.Option
	compress bool
}

// WithAssets passes options to assets.Load: a prefix other than /fw-ui/, application files from an
// fs.FS.
func WithAssets(opts ...assets.Option) Option {
	return func(o *options) { o.assets = append(o.assets, opts...) }
}

// WithViews passes options to views.New: the shell, the title suffix, the login, the flash
// messages, extra image hosts for the page policy (views.WithImageSources, for a map's tiles).
// Build adds views.WithPlainHTTPCookies itself when APP_ENV is local or test.
func WithViews(opts ...views.Option) Option {
	return func(o *options) { o.views = append(o.views, opts...) }
}

// CompressAssets serves the static files compressed (assets.WithCompression): Brotli or gzip from
// Accept-Encoding, each file compressed once and kept, an ETag per encoding, Vary: Accept-Encoding
// (contract section 12). Pages and streams are compressed by wrapping the whole server
// (compress.Handler around srv.Handler()), which passes the compressed files through as they are.
func CompressAssets() Option { return func(o *options) { o.compress = true } }

// Module is the built module.
type Module struct {
	assets *assets.Assets
	views  *views.Views
	routes *httpfw.Routes
	logr   *slog.Logger
}

// Assets is the assets instance (URLs for the pages' own links, Names).
func (m *Module) Assets() *assets.Assets { return m.assets }

// Views is what handlers render pages and answers with.
func (m *Module) Views() *views.Views { return m.views }

// Routes is the module's route table: GET <prefix>{file} for each static file and the sprite alias
// /icons/lucide-sprite.svg. Pass it among the tables of httpfw.NewServer.
func (m *Module) Routes() *httpfw.Routes { return m.routes }

// Build builds the module: it loads the assets, builds the views and the route table. Every problem
// is a *fw.StartupError naming the module.
func (d *Declared) Build(b *fw.Boot, opts ...Option) (*Module, error) {
	return build(b.Environment(), b.Logger(ModuleID), opts)
}

func build(environment string, log *slog.Logger, opts []Option) (*Module, error) {
	var o options
	for _, f := range opts {
		f(&o)
	}
	fail := func(err error) (*Module, error) {
		return nil, &fw.StartupError{Problems: []fw.Problem{{Module: ModuleID, Message: err.Error()}}}
	}
	assetOpts := o.assets
	if o.compress {
		assetOpts = append(slices.Clone(assetOpts), assets.WithCompression())
	}
	a, err := assets.Load(assetOpts...)
	if err != nil {
		return fail(err)
	}
	viewOpts := o.views
	if environment == "local" || environment == "test" {
		viewOpts = append([]views.Option{views.WithPlainHTTPCookies()}, viewOpts...)
	}
	v, err := views.New(a, viewOpts...)
	if err != nil {
		return fail(err)
	}
	files := a.Handler()
	serve := func(w http.ResponseWriter, r *http.Request) error {
		files.ServeHTTP(w, r)
		return nil
	}
	rt := httpfw.NewRoutes(ModuleID)
	rt.Handle("GET "+a.Prefix()+"{file}", serve)
	rt.Handle("GET "+assets.SpriteAliasPath, serve)
	return &Module{assets: a, views: v, routes: rt, logr: log}, nil
}
