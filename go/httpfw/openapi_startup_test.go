package httpfw_test

import (
	"context"
	"errors"
	"io"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// The three mistakes fulfil-go met only when it wrote the document: a plain int, an embedded struct
// and a pointer without omitzero.
type badCounts struct {
	Count int `json:"count"`
}

type badBase struct {
	Name string `json:"name"`
}

type badView struct {
	badBase
	Counts badCounts `json:"counts"`
	Note   *string   `json:"note"`
}

var badOp = httpfw.Get[badView]("/bad").ID("getBad").Build()

// newServerWith builds a server from one table, with the operations declared and bound, and returns
// NewServer's error.
func newServerWith(t *testing.T, declare func(web *httpfw.Declared), bind func(rt *httpfw.Routes), opts ...httpfw.Option) error {
	t.Helper()
	app := fw.New("openapi-startup", fw.WithEnvironment(map[string]string{"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0"}),
		fw.WithConfigDir(t.TempDir()), fw.WithOutput(io.Discard, io.Discard), fw.WithLogger(slogDiscard()))
	web := httpfw.Declare(app)
	declare(web)
	var built error
	run, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		rt := httpfw.NewRoutes("test")
		bind(rt)
		s, err := httpfw.NewServer(b, web, []*httpfw.Routes{rt}, opts...)
		built = err
		if err != nil {
			return nil, err
		}
		return s, nil
	})
	if err == nil {
		t.Cleanup(func() { _ = run.Shutdown() })
	}
	return built
}

func TestABadOpenAPITypeFailsNewServerNamingTheProblem(t *testing.T) {
	t.Parallel()
	err := newServerWith(t, func(web *httpfw.Declared) {
		web.Info("openapi-startup", "1")
		web.Operations(badOp)
	}, func(rt *httpfw.Routes) {
		rt.Bind(httpfw.Handle(badOp, func(context.Context, fw.Context, httpfw.In[httpfw.NoBody]) (badView, error) {
			return badView{}, nil
		}))
	})
	se, ok := errors.AsType[*fw.StartupError](err)
	if !ok {
		t.Fatalf("NewServer: want a *fw.StartupError, got %v", err)
	}
	text := se.Error()
	for _, want := range []string{
		"int is not in the type table",           // the plain int
		"an embedded field is refused",           // the embedded struct
		"note: a pointer field is optional",      // the pointer without omitzero
		"the OpenAPI document cannot be written", // says what failed
	} {
		if !strings.Contains(text, want) {
			t.Errorf("the start-up error does not say %q:\n%s", want, text)
		}
	}
}

func TestAServerWithAGoodDocumentStarts(t *testing.T) {
	t.Parallel()
	if err := newServerWith(t, func(web *httpfw.Declared) {
		web.Info("openapi-startup", "1")
		web.Operations(pingOp)
	}, func(rt *httpfw.Routes) {
		rt.Bind(httpfw.Handle(pingOp, func(context.Context, fw.Context, httpfw.In[httpfw.NoBody]) (itemView, error) {
			return itemView{}, nil
		}))
	}); err != nil {
		t.Fatalf("NewServer: %v", err)
	}
}

// The two checks of the whole document stay with the openapi command (and fwtest.OpenAPI): a server
// built without Info, whose resolver contributes no security scheme, still starts.
func TestAServerWithoutInfoOrASecuritySchemeStarts(t *testing.T) {
	t.Parallel()
	secure := httpfw.Get[itemView]("/secure").ID("getSecure").RequiresContext().Build()
	if err := newServerWith(t, func(web *httpfw.Declared) {
		web.Operations(secure)
	}, func(rt *httpfw.Routes) {
		rt.Bind(httpfw.Handle(secure, func(context.Context, fw.Context, httpfw.In[httpfw.NoBody]) (itemView, error) {
			return itemView{}, nil
		}))
	}, httpfw.WithResolver(fixedContext())); err != nil {
		t.Fatalf("NewServer: %v", err)
	}
}
