package httpfw_test

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// launchWith runs `openapi args...` as a program would: nothing is configured, built or connected.
func launchWith(t *testing.T, declare func(*httpfw.Declared), args ...string) (code int, stdout string) {
	t.Helper()
	var out bytes.Buffer
	app := fw.New("cmd", fw.WithOutput(&out, os.Stderr), fw.WithConfigDir(filepath.Join(t.TempDir(), "no-such-dir")),
		fw.WithEnvironment(map[string]string{"HTTP_PORT": "not a number"})) // a configuration problem would fail any start
	web := httpfw.Declare(app)
	declare(web)
	code = app.Launch(t.Context(), append([]string{"openapi"}, args...), func(context.Context, *fw.Boot) (fw.Serve, error) {
		t.Error("build must not run for a declaration-phase command")
		return nil, nil
	})
	return code, out.String()
}

func TestTheOpenAPICommandNeedsNoConfigurationBuildOrDatabase(t *testing.T) {
	code, out := launchWith(t, func(web *httpfw.Declared) {
		web.Info("cmd", "1")
		web.Operations(pingOp)
	})
	if code != 0 || !strings.HasPrefix(out, "{\n  \"openapi\": \"3.1.0\"") || !strings.HasSuffix(out, "}\n") {
		t.Fatalf("exit %d, output %q", code, out)
	}
}

func TestTheOpenAPICommandWritesAFile(t *testing.T) {
	file := filepath.Join(t.TempDir(), "openapi.json")
	code, out := launchWith(t, func(web *httpfw.Declared) {
		web.Info("cmd", "1")
		web.Operations(pingOp)
	}, "--out", file)
	if code != 0 || out != "" {
		t.Fatalf("exit %d, stdout %q", code, out)
	}
	written, err := os.ReadFile(file)
	if err != nil || !strings.Contains(string(written), `"operationId": "ping"`) {
		t.Fatalf("file: %v %q", err, written)
	}
}

func TestTheOpenAPICommandReportsEveryProblem(t *testing.T) {
	code, out := launchWith(t, func(web *httpfw.Declared) {
		// no Info; an operation with a bad id; a type registered twice
		web.Operations(httpfw.Get[itemView]("/x").ID("Bad Id").Build())
	})
	if code != 1 || out != "" {
		t.Fatalf("exit %d, stdout %q (problems go to standard error)", code, out)
	}
}

func TestTheOpenAPICommandUsage(t *testing.T) {
	code, out := launchWith(t, func(web *httpfw.Declared) { web.Info("cmd", "1") }, "--bogus")
	if code != 2 || !strings.Contains(out, "usage: openapi [--out <file>]") {
		t.Fatalf("exit %d, output %q", code, out)
	}
}

func TestInfoCalledTwiceIsAProblem(t *testing.T) {
	web := httpfw.Declare(fw.New("x"))
	web.Info("a", "1")
	web.Info("b", "2")
	_, problems := web.OpenAPI()
	if len(problems) != 1 || !strings.Contains(problems[0], "Info is called 2 times") {
		t.Fatalf("got %v", problems)
	}
}

func TestADeclarationCommandClaimedTwiceIsAStartupProblem(t *testing.T) {
	app := fw.New("x", fw.WithOutput(os.Stderr, &bytes.Buffer{}))
	httpfw.Declare(app)
	app.Module("other").DeclarationCommand("openapi", func(context.Context, []string, io.Writer) int { return 0 })
	code := app.Launch(t.Context(), []string{"openapi"}, nil)
	if code != 1 {
		t.Fatalf("exit %d", code)
	}
}
