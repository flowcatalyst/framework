package redisfw

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

func TestMain(m *testing.M) { fwtest.Main(m) }

func TestURLRules(t *testing.T) {
	t.Parallel()
	o, err := options("x", "redis://user:pw@cache.example.com/3")
	if err != nil || o.Addr != "cache.example.com:6379" || o.Username != "user" || o.Password != "pw" || o.DB != 3 {
		t.Errorf("options %+v %v", o, err)
	}
	o, err = options("x", "redis://onlysecret@h:6380")
	if err != nil || o.Password != "onlysecret" || o.Username != "" || o.Addr != "h:6380" {
		t.Errorf("user info without ':' must be the password: %+v %v", o, err)
	}
	if o, err := options("x", "rediss://h"); err != nil || o.TLSConfig == nil {
		t.Errorf("rediss: %+v %v", o, err)
	}
	for raw, want := range map[string]string{
		"http://h":     "must start with redis://",
		"redis:///0":   "has no host",
		"redis://h/db": "the url path must be a database number",
	} {
		if _, err := options("x", raw); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%s: err %v, want %q", raw, err, want)
		}
	}
}

func TestConnectThroughAnApplication(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "application.yaml"), []byte("redis:\n  instances:\n    default: { url: placeholder, timeout: 2s }\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	app := fw.New("r", fw.WithEnvironment(map[string]string{"REDIS_INSTANCES_DEFAULT_URL": r.URL}),
		fw.WithConfigDir(dir), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	d := Declare(app)
	var c *Client
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		clients, err := Connect(ctx, b, d)
		if err != nil {
			return nil, err
		}
		def, ok := clients.Default()
		if !ok {
			return nil, context.Canceled
		}
		c = def
		return nil, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if c == nil {
		t.Fatal("build did not run")
	}
	if res, ok := run.Health(t.Context()).Result("redis:default"); !ok || res.Status != fw.Up {
		t.Errorf("health %+v", res)
	}
	_ = run.Shutdown()
	if err := c.Ping(context.Background()); err == nil {
		t.Error("the client is still open after shutdown")
	}
}
