// Command orders-demo-templ is the Go counterpart of the Java orders-demo-jte: the orders pages as a
// server-rendered application on go/ui (templ components, Datastar). It needs no database and no
// configuration file; the same browser check runs against it.
//
//	orders-demo-templ serve --port <free port>
//
// It binds 127.0.0.1 and has no default port: choose one that is free (never 8080).
package main

import (
	"context"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/ui/fwui"
	"github.com/flowcatalyst/framework/go/ui/fwui/examples/orders-demo-templ/internal/orders"
	"github.com/flowcatalyst/framework/go/ui/views"
)

func main() {
	os.Exit(run(context.Background(), os.Args[1:], os.Stderr))
}

func run(ctx context.Context, args []string, stderr io.Writer) int {
	port, err := parseServe(args)
	if err != nil {
		_, _ = fmt.Fprintln(stderr, err)
		return 2
	}
	app := newApp(port)
	return app.Launch(ctx, nil, build(app))
}

// parseServe reads "serve --port N" (or --port=N): there is no default port.
func parseServe(args []string) (int, error) {
	const usage = "usage: orders-demo-templ serve --port <free port>"
	if len(args) < 2 || args[0] != "serve" {
		return 0, fmt.Errorf("%s", usage)
	}
	var value string
	switch rest := args[1:]; {
	case len(rest) == 2 && rest[0] == "--port":
		value = rest[1]
	case len(rest) == 1 && strings.HasPrefix(rest[0], "--port="):
		value = strings.TrimPrefix(rest[0], "--port=")
	default:
		return 0, fmt.Errorf("%s", usage)
	}
	port, err := strconv.Atoi(value)
	if err != nil || port < 1 || port > 65535 {
		return 0, fmt.Errorf("--port %q is not a port number\n%s", value, usage)
	}
	if port == 8080 || port == 15432 {
		return 0, fmt.Errorf("port %d is refused: it belongs to a running fcdev\n%s", port, usage)
	}
	return port, nil
}

func newApp(port int) *fw.App {
	return fw.New("orders-demo-templ", fw.WithEnvironment(map[string]string{
		"APP_ENV": "local", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": strconv.Itoa(port),
	}))
}

func build(app *fw.App) fw.BuildFunc {
	web := httpfw.Declare(app)
	ui := fwui.Declare(app)
	return func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		mod, err := ui.Build(b, fwui.WithViews(views.WithTitleSuffix(" - Orders demo"), views.WithShell(orders.Shell)))
		if err != nil {
			return nil, err
		}
		pages := httpfw.NewRoutes("orders-ui")
		orders.New(mod.Views(), orders.Seeded(b.Clock().Now()), b.Clock()).Register(pages)
		return httpfw.NewServer(b, web, []*httpfw.Routes{mod.Routes(), pages})
	}
}
