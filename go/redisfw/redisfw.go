// Package redisfw is the framework's Redis connections (database-and-redis contract section 3):
// named instances, each one standalone Redis or Valkey server through a go-redis v9 client, a
// start-up ping and the critical health check redis:<name>. Redis Cluster and Sentinel are out of
// scope.
//
//	rd := redisfw.Declare(app)                  // in main
//	clients, err := redisfw.Connect(ctx, b, rd) // in build
package redisfw

import (
	"context"
	"crypto/tls"
	"fmt"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

// ModuleID is the Redis module's id.
const ModuleID = "redis"

var (
	keyURL     = config.String("url", "redis://[user[:password]@]host[:port][/db] or rediss://...").Required()
	keyTimeout = config.Duration("timeout", "Connect, read and write timeout (more than 0, at most 5m).").
			WithDefault(2 * time.Second).Checked(config.Between(time.Nanosecond, 5*time.Minute))
	keyPoolMax  = config.Int("pool.max", "Most connections in the pool (1 to 1000).").WithDefault(16).Checked(config.Between(1, 1000))
	keyPassword = config.SecretValue("password", "Password; wins over one in the URL.")
)

// Declared is the Redis module's declaration, from [Declare].
type Declared struct {
	instances fw.InstancesHandle
}

// Declare declares the Redis module: the instance section "redis" with its keys.
func Declare(app *fw.App) *Declared {
	d := app.Module(ModuleID)
	return &Declared{instances: d.Instances("redis", "Redis", keyURL, keyTimeout, keyPoolMax, keyPassword)}
}

// Client is one Redis instance.
type Client struct {
	name   string
	client *redis.Client
}

// Name returns the instance's name.
func (c *Client) Name() string { return c.name }

// Redis returns the go-redis client, for the lock and rate-limit drivers and the application.
func (c *Client) Redis() *redis.Client { return c.client }

// Ping checks that the server answers.
func (c *Client) Ping(ctx context.Context) error { return c.client.Ping(ctx).Err() }

// Clients are the configured Redis instances.
type Clients struct {
	names  []string
	def    string
	byName map[string]*Client
}

// Get returns the named instance, or an error naming the known instances.
func (c *Clients) Get(name string) (*Client, error) {
	if x, ok := c.byName[name]; ok {
		return x, nil
	}
	known := "(none)"
	if len(c.names) > 0 {
		known = strings.Join(c.names, ", ")
	}
	return nil, fmt.Errorf("no Redis instance named '%s'; known instances: %s", name, known)
}

// Default returns the default instance, and false when none is configured.
func (c *Clients) Default() (*Client, bool) {
	x, ok := c.byName[c.def]
	if !ok || x == nil {
		return nil, false
	}
	return x, true
}

// Names returns the instances' names in configuration order.
func (c *Clients) Names() []string { return slices.Clone(c.names) }

// Connect opens a client per configured instance and pings it; one that does not answer fails
// the start, naming it. Each gets the critical health check redis:<name> and is closed at
// shutdown.
func Connect(ctx context.Context, b *fw.Boot, d *Declared) (*Clients, error) {
	insts := b.Instances(d.instances)
	out := &Clients{names: slices.Clone(insts.Names), def: insts.Default, byName: make(map[string]*Client)}
	for _, name := range insts.Names {
		in := insts.ByName[name]
		opts, err := options(name, keyURL.Get(in))
		if err != nil {
			return nil, err
		}
		if pw, ok := keyPassword.Lookup(in); ok {
			opts.Password = pw.Reveal()
		}
		timeout := keyTimeout.Get(in)
		opts.DialTimeout, opts.ReadTimeout, opts.WriteTimeout = timeout, timeout, timeout
		opts.PoolSize = keyPoolMax.Get(in)
		c := &Client{name: name, client: redis.NewClient(opts)}
		b.OnShutdown("redis:"+name, func(context.Context) error { return c.client.Close() })
		if err := c.Ping(ctx); err != nil {
			return nil, fmt.Errorf("cannot connect to redis instance '%s': %w", name, err)
		}
		b.Health("redis:"+name, fw.Critical, c.Ping)
		out.byName[name] = c
	}
	return out, nil
}

// Open opens one client from a URL outside an application (tools, tests), pinged.
func Open(ctx context.Context, name, rawURL string) (*Client, error) {
	opts, err := options(name, rawURL)
	if err != nil {
		return nil, err
	}
	c := &Client{name: name, client: redis.NewClient(opts)}
	if err := c.Ping(ctx); err != nil {
		_ = c.client.Close()
		return nil, fmt.Errorf("cannot connect to redis instance '%s': %w", name, err)
	}
	return c, nil
}

// Close closes the client (an application's clients are closed at shutdown; this is for Open).
func (c *Client) Close() error { return c.client.Close() }

// options reads a redis:// or rediss:// URL as the Java module does: user and password from the
// user info, the database number from the path, port 6379 by default.
func options(name, raw string) (*redis.Options, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return nil, fmt.Errorf("redis instance '%s': url is not a valid URI", name)
	}
	if u.Scheme != "redis" && u.Scheme != "rediss" {
		return nil, fmt.Errorf("redis instance '%s': url must start with redis:// or rediss://", name)
	}
	if u.Hostname() == "" {
		return nil, fmt.Errorf("redis instance '%s': url has no host", name)
	}
	port := u.Port()
	if port == "" {
		port = "6379"
	}
	opts := &redis.Options{Addr: u.Hostname() + ":" + port}
	if u.User != nil {
		opts.Username = u.User.Username()
		if pw, ok := u.User.Password(); ok {
			opts.Password = pw
		} else if opts.Username != "" {
			// redis://secret@host: the user info without ':' is the password (Java's rule)
			opts.Password, opts.Username = opts.Username, ""
		}
	}
	if p := strings.TrimPrefix(u.Path, "/"); p != "" {
		db, err := strconv.Atoi(p)
		if err != nil {
			return nil, fmt.Errorf("redis instance '%s': the url path must be a database number", name)
		}
		opts.DB = db
	}
	if u.Scheme == "rediss" {
		opts.TLSConfig = &tls.Config{ServerName: u.Hostname(), MinVersion: tls.VersionTLS12}
	}
	return opts, nil
}
