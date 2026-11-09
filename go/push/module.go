package push

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/internal/pushrow"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/usecase"
)

// ModuleID is the push module's id: its migration set and its configuration component.
const ModuleID = "push"

// platformClient is how a stream token and a ticket name the platform client.
var platformClient = fw.PlatformClient.String()

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrations returns the module's migration set: fw_push_outbox and fw_push_log.
func Migrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // embedded above
	}
	return sub
}

// Driver is the value of push.driver.
type Driver string

// The log drivers (push contract section 4).
const (
	DriverPostgres Driver = "postgres"
	DriverRedis    Driver = "redis"
)

// Declared is the push module's declaration, from [Declare]: its settings and its topic rules.
type Declared struct {
	driver          config.Handle[Driver]
	redisInstance   config.Handle[string]
	retention       config.Handle[time.Duration]
	maxDataBytes    config.Handle[int]
	maxTopics       config.Handle[int]
	relayBatch      config.Handle[int]
	relayInterval   config.Handle[time.Duration]
	relayLease      config.Handle[time.Duration]
	streamBuffer    config.Handle[int]
	maxLifetime     config.Handle[time.Duration]
	recent          config.Handle[int]
	maxStreams      config.Handle[int]
	maxPerPrincipal config.Handle[int]
	streamSecret    config.Handle[config.Secret]
	ticketKey       config.Handle[config.Secret]

	rules    []Rule
	problems []string
}

func atLeastOne(v int) string {
	if v < 1 {
		return fmt.Sprintf("must be at least 1, was %d", v)
	}
	return ""
}

// Declare declares the push module (push contract section 10): the settings push.*, the claim on
// the configuration component "push" (a misspelt key under it fails start-up) and the migration set
// (module push, applied to the default database instance).
func Declare(app *fw.App) *Declared {
	m := app.Module(ModuleID)
	m.ClaimComponent("push")
	m.Migrations(Migrations())
	return &Declared{
		driver: config.Declare(m, config.Enum("push.driver", "Where the push log lives: postgres (fw_push_log) or redis (a Redis stream).",
			DriverPostgres, DriverRedis).WithDefault(DriverPostgres)),
		redisInstance: config.Declare(m, config.String("push.redis-instance",
			"The Redis instance of the redis driver (default: the default instance).")),
		retention: config.Declare(m, config.Duration("push.retention",
			"How long the log keeps a push for clients that resume (10m to 24h).").
			WithDefault(24*time.Hour).Checked(config.Between(10*time.Minute, 24*time.Hour))),
		maxDataBytes: config.Declare(m, config.Int("push.max-data-bytes",
			"Largest push data, in bytes of JSON (1 to 65536).").WithDefault(pushrow.DefaultMaxDataBytes).Checked(config.Between(1, pushrow.MaxDataBytes))),
		maxTopics: config.Declare(m, config.Int("push.max-topics",
			"Most topics one subscribe may ask for (1 to 1000).").WithDefault(100).Checked(config.Between(1, 1000))),
		relayBatch: config.Declare(m, config.Int("push.relay.batch",
			"Most outbox rows one relay run moves (1 to 5000).").WithDefault(500).Checked(config.Between(1, 5000))),
		relayInterval: config.Declare(m, config.Duration("push.relay.interval",
			"Longest wait between relay runs, and the hubs' poll (50ms to 10s).").
			WithDefault(time.Second).Checked(config.Between(50*time.Millisecond, 10*time.Second))),
		relayLease: config.Declare(m, config.Duration("push.relay.lease",
			"The relay lock's lease: how long pushes wait to be logged after the relay's instance died; refreshed every third of it (1s to 60s).").
			WithDefault(5*time.Second).Checked(config.Between(time.Second, time.Minute))),
		streamBuffer: config.Declare(m, config.Int("push.stream.buffer",
			"Pushes a stream may fall behind before it is ended (16 to 10000).").WithDefault(256).Checked(config.Between(16, 10_000))),
		maxLifetime: config.Declare(m, config.Duration("push.stream.max-lifetime",
			"Longest life of a stream token (1m to 24h).").WithDefault(time.Hour).Checked(config.Between(time.Minute, 24*time.Hour))),
		recent: config.Declare(m, config.Int("push.recent",
			"Recent pushes each instance keeps in memory for replays (0 to 1000000).").WithDefault(10_000).Checked(config.Between(0, 1_000_000))),
		maxStreams: config.Declare(m, config.Int("push.max-streams",
			"Most streams open on one instance (at least 1).").WithDefault(50_000).Checked(atLeastOne)),
		maxPerPrincipal: config.Declare(m, config.Int("push.max-streams-per-principal",
			"Most streams of one principal on one instance (at least 1).").WithDefault(20).Checked(atLeastOne)),
		streamSecret: config.Declare(m, config.SecretValue("push.stream.secret",
			"The key that signs stream tokens (HMAC-SHA256), at least 32 characters, the same on every instance.").Required()),
		ticketKey: config.Declare(m, config.SecretValue("push.ticket.key",
			"Ticket signing keys: <kid>:<base64url of a 32-byte Ed25519 seed>, comma-separated, the current key first, then the previous one.").Required()),
	}
}

// Topic declares a rule (push contract 6.3): who may subscribe to the topics of pattern without a
// ticket. A pattern with neither {principal} nor [Requires] is refused (a start-up problem); its
// first segment must be the application's code.
//
//	pd.Topic("fulfil:order:{id}", push.Requires("fulfil:order:view")) // any order of the stream's client
//	pd.Topic("fulfil:user:{principal}")                              // only your own
func (d *Declared) Topic(pattern string, opts ...RuleOption) {
	r, err := NewRule(pattern, opts...)
	if err != nil {
		d.problems = append(d.problems, err.Error())
		return
	}
	d.rules = append(d.rules, r)
}

// PublicTopic declares a deliberately open topic pattern: anyone may subscribe, with or without
// credentials.
func (d *Declared) PublicTopic(pattern string) {
	r, err := NewPublicRule(pattern)
	if err != nil {
		d.problems = append(d.problems, err.Error())
		return
	}
	d.rules = append(d.rules, r)
}

// Option configures [Declared.Build].
type Option func(*options)

type options struct {
	auth     Authenticator
	app      string
	base     string
	redisLog func(ctx context.Context, b *fw.Boot, instance string) (Log, error)
}

// Authenticate sets how a subscribe request is authenticated: [AuthModule] for the auth module.
// Without it every subscribe is unauthenticated and needs tickets.
func Authenticate(a Authenticator) Option { return func(o *options) { o.auth = a } }

// Application sets the application code: the first segment of every topic, the iss of the tickets
// this host mints and the only issuer it accepts. Default: the application's name.
func Application(code fw.ApplicationCode) Option {
	return func(o *options) { o.app = code.String() }
}

// BasePath puts the routes under a path ("/api" serves /api/push/subscriptions). Default: none.
func BasePath(path string) Option { return func(o *options) { o.base = path } }

// RedisLog gives the A2 driver, used when push.driver is redis; it gets push.redis-instance ("" for
// the default instance).
func RedisLog(f func(ctx context.Context, b *fw.Boot, instance string) (Log, error)) Option {
	return func(o *options) { o.redisLog = f }
}

type settings struct {
	maxData, maxTopics int
	retention          time.Duration
}

// Pushes is the built push module: publishing, tickets, the routes and the stream ends.
type Pushes struct {
	app     string
	db      *database.DB
	log     Log
	hub     *hub
	relay   *relay
	granter *granter
	tokens  streamTokens
	keysSet *ticketKeys
	jwks    []byte
	auth    Authenticator
	clock   fw.Clock
	logger  *slog.Logger
	metrics *hubMetrics
	cfg     settings
	routes  *httpfw.Routes
}

// Build builds the module: db is the default database instance (the outbox, and the A1 log), lk
// the lock instances (the relay is gated by svc:push-relay on the default one). It registers the
// loops push-relay (gated, with the lease push.relay.lease; a relay that stops cleanly releases the
// lock) and push-retention, the health check push:log, the hub (started with the
// application, stopped at shutdown) and the routes. Every problem found is reported at once as a
// *fw.StartupError.
func (d *Declared) Build(ctx context.Context, b *fw.Boot, db *database.DB, lk *locks.Locks, opts ...Option) (*Pushes, error) {
	o := options{app: b.AppName()}
	for _, f := range opts {
		f(&o)
	}
	var problems []fw.Problem
	bad := func(key, msg string) {
		problems = append(problems, fw.Problem{Module: ModuleID, Key: key, Message: msg})
	}
	for _, msg := range d.problems {
		bad("", msg)
	}
	if !pushrow.IsApplication(o.app) {
		bad("", "the application code must match [a-z][a-z0-9-]*: "+o.app)
	}
	for _, r := range d.rules {
		if r.pat.application() != o.app {
			bad("", fmt.Sprintf("push rule %s: a topic starts with the application's code (%s)", r.Pattern(), o.app))
		}
	}
	if o.base != "" && (!strings.HasPrefix(o.base, "/") || strings.HasSuffix(o.base, "/")) {
		bad("", "the base path starts with / and does not end with one: "+o.base)
	}
	if db == nil || lk == nil {
		bad("", "push needs the default database instance and the lock instances")
	}
	secret := d.streamSecret.Get(b).Reveal()
	if len(secret) < 32 {
		bad("push.stream.secret", "must be at least 32 characters")
	}
	keys, err := parseTicketKeys(d.ticketKey.Get(b).Reveal())
	if err != nil {
		bad("push.ticket.key", err.Error())
	}
	var gate *locks.Gate
	if lk != nil {
		if gate, err = locks.ServiceGate(lk, relayLoop, d.relayLease.Get(b)); err != nil {
			bad("", "the relay's lock: "+err.Error())
		}
	}
	driver := d.driver.Get(b)
	if driver == DriverRedis && o.redisLog == nil {
		bad("push.driver", "is redis, but Build was not given push.RedisLog(...)")
	}
	if len(problems) > 0 || keys == nil {
		return nil, &fw.StartupError{Problems: problems}
	}

	var log Log = NewPostgresLog(db)
	if driver == DriverRedis {
		instance, _ := d.redisInstance.Lookup(b)
		if log, err = o.redisLog(ctx, b, instance); err != nil {
			return nil, fmt.Errorf("push: the redis log: %w", err)
		}
	}
	m, err := newHubMetrics(b.Metrics())
	if err != nil {
		return nil, err
	}
	logger := b.Logger(ModuleID)
	interval := d.relayInterval.Get(b)
	jwks, err := keys.jwks()
	if err != nil {
		return nil, err
	}
	p := &Pushes{
		app: o.app, db: db, log: log, auth: o.auth, clock: b.Clock(), logger: logger, metrics: m,
		tokens: streamTokens{secret: []byte(secret)}, keysSet: keys, jwks: jwks,
		cfg:     settings{maxData: d.maxDataBytes.Get(b), maxTopics: d.maxTopics.Get(b), retention: d.retention.Get(b)},
		granter: &granter{application: o.app, rules: append([]Rule(nil), d.rules...), keys: keys, maxLifetime: d.maxLifetime.Get(b)},
	}
	p.hub = newHub(log, logger, b.Clock(), m, hubConfig{
		buffer: d.streamBuffer.Get(b), recent: d.recent.Get(b), maxStreams: d.maxStreams.Get(b),
		maxPerPrincipal: d.maxPerPrincipal.Get(b), poll: interval,
	})
	// the last id and the newest push.recent pushes, before anything is served
	if err := p.hub.warm(ctx); err != nil {
		return nil, err
	}
	p.relay = &relay{db: db, log: log, clock: b.Clock(), m: m, batch: d.relayBatch.Get(b), interval: interval}

	hubCtx, stopHub := context.WithCancel(context.WithoutCancel(ctx))
	hubDone := make(chan struct{})
	var hubStarted atomic.Bool
	b.OnStarted("push:hub", func(ctx context.Context) error {
		hubStarted.Store(true)
		go func() {
			defer close(hubDone)
			p.hub.run(hubCtx)
		}()
		// streams open only once the hub follows the log, so no end of streams is missed; if the
		// driver cannot follow yet, the hub polls and the start goes on
		wait := time.NewTimer(10 * time.Second)
		defer wait.Stop()
		select {
		case <-p.hub.following:
		case <-wait.C:
			logger.Warn("push: the hub does not follow the log yet; it polls every push.relay.interval meanwhile")
		case <-ctx.Done():
			return ctx.Err()
		}
		return nil
	})
	b.OnShutdown("push:hub", func(context.Context) error {
		stopHub()
		if hubStarted.Load() {
			<-hubDone
		}
		return nil
	})
	b.Loop(relayLoop, background.Options{Interval: interval, Gate: gate}, p.relay.run)
	b.Loop("push-retention", background.Options{Interval: time.Minute}, func(ctx context.Context, _ background.Tick) error {
		_, err := log.Prune(ctx, p.clock.Now().Add(-p.cfg.retention))
		return err
	})
	b.Health("push:log", fw.Informational, log.Ping)

	rt := httpfw.NewRoutes(ModuleID)
	rt.Handle("POST "+o.base+"/push/subscriptions", p.subscribe)
	rt.Handle("GET "+o.base+"/push/stream", p.listen)
	rt.Handle("GET "+o.base+"/push/keys", p.keys)
	p.routes = rt
	return p, nil
}

// Routes is the module's route table: POST {base}/push/subscriptions, GET {base}/push/stream and
// GET {base}/push/keys. Pass it among the tables of httpfw.NewServer.
func (p *Pushes) Routes() *httpfw.Routes { return p.routes }

// Rules returns the declared topic rules (data, for a gateway to apply).
func (p *Pushes) Rules() []Rule { return append([]Rule(nil), p.granter.rules...) }

// RunnerOption passes push.max-data-bytes to the use-case runner, so usecase.Push refuses what
// Publish refuses: usecase.NewRunner(b, outbox, db, pushes.RunnerOption()).
func (p *Pushes) RunnerOption() usecase.RunnerOption {
	return usecase.WithPushMaxDataBytes(p.cfg.maxData)
}

// Publish writes a push in a transaction the caller owns (push contract section 2), for cx's
// client: a row of fw_push_outbox and a notification, both only if tx commits. A use case uses
// usecase.Push instead. The error is an [*InvalidPushError] for data over push.max-data-bytes, or
// the database's.
func (p *Pushes) Publish(ctx context.Context, tx pgx.Tx, cx fw.Context, push Push) error {
	if cx.IsZero() {
		return fw.ErrZeroContext
	}
	if push.IsZero() {
		return errors.New("push: Publish of the zero Push (use push.New)")
	}
	if len(push.data) > p.cfg.maxData {
		return &InvalidPushError{Reason: pushrow.DataTooLarge, Message: fmt.Sprintf("the data is %d bytes, more than %d (push.max-data-bytes)", len(push.data), p.cfg.maxData)}
	}
	return pushrow.Insert(ctx, tx, push.topic, push.typ, cx.Client(), push.data, p.clock.Now())
}

// PublishAlone is [Pushes.Publish] in a transaction of its own on the default database instance.
func (p *Pushes) PublishAlone(ctx context.Context, cx fw.Context, push Push) error {
	return pgx.BeginTxFunc(ctx, p.db.Pool(), pgx.TxOptions{IsoLevel: pgx.ReadCommitted}, func(tx pgx.Tx) error {
		return p.Publish(ctx, tx, cx, push)
	})
}

// Cursor returns the push cursor for a snapshot (push contract section 9.1): the id of the newest
// push this instance has dispatched, as a decimal string, "0" for an empty log. An endpoint that
// returns state a client keeps current by pushes returns the cursor with it, and the client opens
// its first listen with the cursor as Last-Event-ID (field-js: the lastEventId option of
// PushStream), so a change committed between the read and the listen is not missed.
//
// Call it BEFORE reading the state: before the read transaction takes its snapshot (in a REPEATABLE
// READ transaction, before its first statement; in READ COMMITTED, before the state query). Every
// push at or below the cursor was in the log when the cursor was read, and the relay logs a push
// only after the publishing transaction committed, so the change behind it is visible to a state
// read that starts afterwards. A change the snapshot lacks therefore has a push above the cursor,
// and the listen delivers it. A push for a change the snapshot already holds is harmless: pushes
// are signals to read again.
//
// The cursor may lag the log (this instance has not dispatched the newest pushes yet). That is safe,
// since a lagging cursor only replays more pushes; it costs no I/O.
func (p *Pushes) Cursor() string {
	return strconv.FormatInt(p.hub.lastID(), 10)
}

// TicketOptions binds a ticket further ([Pushes.TicketWith]).
type TicketOptions struct {
	// ProofKey binds the ticket to a DPoP key (cnf.jkt): the subscribe request must be
	// authenticated with a field token bound to it (auth.FieldPrincipal.KeyThumbprint).
	ProofKey string
	// StreamExpiry is the latest the stream may live (stream-exp); zero for the default, exp plus
	// push.stream.max-lifetime.
	StreamExpiry time.Time
}

// Ticket mints a ticket (push contract 6.4) granting topics (patterns as in rules, each placeholder
// matching any one segment, at most 100) to whoever holds it, for cx's client, valid for
// [TicketTTL]. Mint it in the application's own endpoint, after its own access check, for an
// authenticated caller only.
func (p *Pushes) Ticket(cx fw.Context, topics ...string) (string, error) {
	return p.TicketWith(cx, TicketOptions{}, topics...)
}

// TicketWith is [Pushes.Ticket] with a key binding or a stream expiry.
func (p *Pushes) TicketWith(cx fw.Context, o TicketOptions, topics ...string) (string, error) {
	if cx.IsZero() {
		return "", fw.ErrZeroContext
	}
	if len(topics) == 0 || len(topics) > MaxTicketTopics {
		return "", fmt.Errorf("push: a ticket grants 1 to %d topic patterns", MaxTicketTopics)
	}
	for _, t := range topics {
		pat, err := parsePattern(t)
		if err != nil {
			return "", fmt.Errorf("push: %w", err)
		}
		if pat.application() != p.app {
			return "", fmt.Errorf("push: a ticket grants this application's topics (%s) only: %s", p.app, t)
		}
	}
	now := p.clock.Now()
	c := ticketClaims{
		Iss: p.app, Aud: TicketAudience, Client: cx.Client().String(), Topics: topics,
		Iat: now.Unix(), Exp: now.Add(TicketTTL).Unix(),
	}
	if pr, ok := cx.Principal(); ok {
		c.Sub = pr.String()
	}
	if o.ProofKey != "" {
		c.Cnf = &cnfClaim{JKT: o.ProofKey}
	}
	if !o.StreamExpiry.IsZero() {
		c.StreamExp = o.StreamExpiry.Unix()
	}
	return p.keysSet.sign(c)
}

// EndStreams ends the open streams of a holder on every instance, with auth-expired (push contract
// section 7: a revoked field login family, a session signed out). The clients subscribe again.
func (p *Pushes) EndStreams(ctx context.Context, h Holder) error {
	if h.IsZero() {
		return errors.New("push: EndStreams of a holder that names nobody")
	}
	p.hub.endHolder(h)
	return p.log.End(ctx, h)
}

// EndPrincipal is EndStreams for one principal.
func (p *Pushes) EndPrincipal(ctx context.Context, principal string) error {
	return p.EndStreams(ctx, Holder{Principal: principal})
}
