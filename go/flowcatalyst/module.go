package flowcatalyst

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/locks"
)

// BuildOption gives [Build] the capabilities the module uses when the application has them.
type BuildOption func(*buildConfig)

type buildConfig struct {
	dbs   *database.Databases
	locks *locks.Locks
}

// WithDatabases lets the module watch the outbox (the outbox health check, on the default instance,
// when it has an outbox_messages table) and purge old Once records (when it has fw_dispatch_once).
// Build after the migrations ran, so both tables exist.
func WithDatabases(dbs *database.Databases) BuildOption { return func(c *buildConfig) { c.dbs = dbs } }

// WithLocks gives the module the lock stores: the scheduled-job overlap lock and the single-replica
// purge use the default store. Handlers that are not JobOptions.AlwaysConcurrent need one.
func WithLocks(l *locks.Locks) BuildOption { return func(c *buildConfig) { c.locks = l } }

// Platform is the built module: the platform client, the validated definitions, the receivers and
// the sync. It is made by [Build].
type Platform struct {
	client  *PlatformClient
	catalog *Catalog
	recv    *Receivers
	s       settings
	b       *fw.Boot
	ctx     context.Context
	log     *slog.Logger
	cfg     buildConfig

	targetBase string
	ready      atomic.Bool
	inFlight   sync.WaitGroup
	runner     *jobRunner
	routes     *httpfw.Routes
	mounted    bool
	bg         sync.WaitGroup
}

// Client returns the platform client; on a disabled module every method but Enabled fails with
// [ErrDisabled].
func (p *Platform) Client() *PlatformClient { return p.client }

// Catalog returns the validated definitions (empty when the module is disabled).
func (p *Platform) Catalog() *Catalog { return p.catalog }

// Receivers returns the registry to register handlers in, before [Platform.Routes].
func (p *Platform) Receivers() *Receivers { return p.recv }

// Build makes the module (flowcatalyst contract sections 1 to 4): the platform client, the
// validated definitions, the receivers' registry, the `sync` command, the `flowcatalyst` and
// `outbox` health checks (informational) and the purge of old Once records, and registers the
// start-up and shutdown work. Every missing or malformed setting and every invalid definition is
// reported at once as a *fw.StartupError. With flowcatalyst.enabled false nothing but the sync
// command (which exits 2) is made.
func Build(ctx context.Context, b *fw.Boot, d *Declared, opts ...BuildOption) (*Platform, error) {
	var cfg buildConfig
	for _, o := range opts {
		o(&cfg)
	}
	p := &Platform{s: d.s, b: b, ctx: context.WithoutCancel(ctx), log: b.Logger(ModuleID), cfg: cfg, recv: newReceivers(), catalog: &Catalog{}}
	b.Command("sync", p.syncCommand)
	if !d.s.enabled.Get(b) {
		p.client = disabledClient()
		p.log.Info("flowcatalyst is disabled (flowcatalyst.enabled=false)")
		b.OnStarted("flowcatalyst:open", p.open)
		return p, nil
	}
	problems := p.validateSettings(b)
	var app fw.ApplicationCode
	if a, ok := d.s.application.Lookup(b); ok {
		var err error
		if app, err = fw.ParseApplicationCode(a); err != nil {
			problems = append(problems, fw.Problem{Module: ModuleID, Key: d.s.application.Setting().Key(), Message: "must match [a-z][a-z0-9-]*"})
		}
	}
	target, hasTarget := d.s.targetBaseURL.Lookup(b)
	if len(problems) == 0 {
		cat, ps := buildCatalog(d.reg.all(), app, hasTarget)
		problems = append(problems, ps...)
		p.catalog = cat
	}
	if len(problems) > 0 {
		return nil, &fw.StartupError{Problems: problems}
	}
	if hasTarget {
		p.targetBase = target.String()
	}
	baseURL, _ := d.s.baseURL.Lookup(b)
	secret, _ := d.s.clientSecret.Lookup(b)
	clientID, _ := d.s.clientID.Lookup(b)
	p.client = newPlatformClient(baseURL, app, clientID, secret, d.s.timeout.Get(b))

	// informational: the platform is not on the request path; with receiver handlers open the check
	// also needs the service principal (a token that does not name one runs no handler)
	b.Health(PlatformCheckName, fw.Informational, func(ctx context.Context) error {
		if err := p.client.VerifyCredentials(ctx); err != nil {
			return err
		}
		if p.ready.Load() && (p.recv.hasDispatch() || p.recv.hasJobs()) {
			if _, err := p.client.ServicePrincipal(ctx); err != nil {
				return err
			}
		}
		return nil
	})
	p.addOutboxCheck(ctx, b)
	p.addPurge(ctx, b)
	b.OnStarted("flowcatalyst:open", p.open)
	b.OnShutdown("flowcatalyst", p.shutdown)
	return p, nil
}

// validateSettings reports the settings that are required while enabled.
func (p *Platform) validateSettings(b *fw.Boot) []fw.Problem {
	s := p.s
	var ps []fw.Problem
	missing := func(key string) {
		ps = append(ps, fw.Problem{Module: ModuleID, Key: key, Message: "required while flowcatalyst.enabled is true"})
	}
	base, hasBase := s.baseURL.Lookup(b)
	switch {
	case !hasBase:
		missing(s.baseURL.Setting().Key())
	case (base.Scheme != "http" && base.Scheme != "https") || base.Hostname() == "":
		ps = append(ps, fw.Problem{Module: ModuleID, Key: s.baseURL.Setting().Key(), Message: "must be an absolute http or https URL"})
	}
	if _, ok := s.application.Lookup(b); !ok {
		missing(s.application.Setting().Key())
	}
	if _, ok := s.clientID.Lookup(b); !ok {
		missing(s.clientID.Setting().Key())
	}
	if _, ok := s.clientSecret.Lookup(b); !ok {
		missing(s.clientSecret.Setting().Key())
	}
	return ps
}

func (p *Platform) addOutboxCheck(ctx context.Context, b *fw.Boot) {
	db := p.defaultDB()
	if db == nil {
		p.log.Debug("flowcatalyst: no database instance, so no 'outbox' health check")
		return
	}
	present, err := db.TableExists(ctx, outboxTable)
	if err != nil {
		// unknown: add the check, which reports the database error rather than hiding it
		p.log.Warn(fmt.Sprintf("flowcatalyst: could not look for table %s in database instance '%s': %v", outboxTable, db.Name(), err))
		present = true
	}
	if !present {
		p.log.Info(fmt.Sprintf("flowcatalyst: database instance '%s' has no %s table, so no 'outbox' health check", db.Name(), outboxTable))
		return
	}
	b.Health(OutboxCheckName, fw.Informational, outboxCheck(db, p.s.staleAfter.Get(b), b.Clock()))
}

func (p *Platform) defaultDB() *database.DB {
	if p.cfg.dbs == nil {
		return nil
	}
	db, ok := p.cfg.dbs.Default()
	if !ok {
		return nil
	}
	return db
}

func (p *Platform) defaultLock() *locks.Lock {
	if p.cfg.locks == nil {
		return nil
	}
	l, ok := p.cfg.locks.Default()
	if !ok {
		return nil
	}
	return l
}

// addPurge registers the loop that deletes old Once records, when the database has the table.
func (p *Platform) addPurge(ctx context.Context, b *fw.Boot) {
	db := p.defaultDB()
	if db == nil {
		return
	}
	present, err := db.TableExists(ctx, purgeTable)
	if err != nil {
		p.log.Warn(fmt.Sprintf("flowcatalyst: could not look for table %s: %v; no purge task", purgeTable, err))
		return
	}
	if !present {
		p.log.Info(fmt.Sprintf("flowcatalyst: no %s table in '%s', so no purge task", purgeTable, db.Name()))
		return
	}
	retention := p.s.rOnceRetention.Get(b)
	purge := &oncePurge{db: db, retention: retention, now: b.Clock().Now}
	opts := background.Options{Interval: p.s.rOncePurge.Get(b)}
	if lock := p.defaultLock(); lock != nil {
		key, err := purgeGateKey()
		if err != nil {
			p.log.Warn("flowcatalyst: " + err.Error())
			return
		}
		// several replicas: the run takes the service lock without waiting and is skipped if it is held
		gate, err := locks.NewGate(lock, key, 5*time.Minute)
		if err != nil {
			p.log.Warn("flowcatalyst: " + err.Error())
			return
		}
		opts.Gate = gate
	}
	b.Loop("flowcatalyst-once-purge", opts, func(ctx context.Context, _ background.Tick) error {
		n, err := purge.runOnce(ctx)
		if n > 0 {
			p.log.Info(fmt.Sprintf("flowcatalyst: purged %d %s rows older than %v", n, purgeTable, retention))
		}
		if err != nil {
			p.log.Warn(fmt.Sprintf("flowcatalyst: purging %s failed (%v); will retry", purgeTable, err))
		}
		return nil // never stops the task: the next run tries again
	})
}

// Routes returns the receivers' routes, to hand to httpfw.NewServer, and seals the registry: a
// handler registered later is [ErrSealed]. Only a receiver that has handlers is routed (every
// method of the other answers 404 as an unknown path, before any signature work), and with
// flowcatalyst.receivers.enabled false nothing is: start-up then warns which handlers can never be
// called. The routes are POST {flowcatalyst.receivers.path}/dispatch and
// {path}/scheduled-jobs; deliveries are answered 503 until start-up has checked the registrations.
func (p *Platform) Routes() (*httpfw.Routes, error) {
	if p.routes != nil {
		return p.routes, nil
	}
	p.recv.seal()
	rt := httpfw.NewRoutes(ModuleID)
	p.routes = rt
	if !p.client.Enabled() {
		return rt, nil
	}
	if !p.s.rEnabled.Get(p.b) {
		p.log.Info(fmt.Sprintf("flowcatalyst: %s is false, so the receiver routes are not mounted", p.s.rEnabled.Setting().Key()))
		return rt, nil
	}
	prefix := p.s.rPath.Get(p.b)
	require := p.s.rRequireSig.Get(p.b)
	if !require {
		p.log.Warn(fmt.Sprintf("flowcatalyst: %s is false: unsigned deliveries to %s are accepted. Anyone who can reach these routes can run your handlers.",
			p.s.rRequireSig.Setting().Key(), prefix))
	}
	deps := func(route string, secret, shared, bearer, sharedBearer config.Handle[config.Secret]) receiverDeps {
		g := gate{require: require, clock: p.b.Clock()}
		g.signing, g.hasSigning = p.effective(secret, shared)
		g.bearer, g.hasBearer = p.effective(bearer, sharedBearer)
		return receiverDeps{recv: p.recv, gate: g, app: p.client.app, principal: p.client.ServicePrincipal, ready: &p.ready, log: p.log, route: route}
	}
	if p.recv.hasDispatch() {
		route := prefix + "/dispatch"
		dr := &dispatchReceiver{
			receiverDeps: deps(route, p.s.rDispatchSecret, p.s.rSigningSecret, p.s.rDispatchBearer, p.s.rBearerToken),
			timeout:      p.s.rDispatchTO.Get(p.b), inFlight: &p.inFlight,
		}
		rt.Handle("POST "+route, dr.handle)
		p.mounted = true
	}
	if p.recv.hasJobs() {
		route := prefix + "/scheduled-jobs"
		p.runner = newJobRunner(p.ctx, p.client, p.client.app, p.cfg.locks, p.s.rJobTO.Get(p.b), p.s.rLockLease.Get(p.b), p.s.rRetryDelay.Get(p.b), p.log)
		jr := &jobReceiver{receiverDeps: deps(route, p.s.rJobsSecret, p.s.rSigningSecret, p.s.rJobsBearer, p.s.rBearerToken), runner: p.runner, platform: p.client}
		rt.Handle("POST "+route, jr.handle)
		p.mounted = true
	}
	return rt, nil
}

// effective is the receiver's own secret, else the shared one; a blank value counts as unset.
func (p *Platform) effective(own, shared config.Handle[config.Secret]) (config.Secret, bool) {
	if s, ok := own.Lookup(p.b); ok && strings.TrimSpace(s.Reveal()) != "" {
		return s, true
	}
	if s, ok := shared.Lookup(p.b); ok && strings.TrimSpace(s.Reveal()) != "" {
		return s, true
	}
	return config.Secret{}, false
}

// open runs when start-up reaches the started actions: it seals the registry, checks the
// registrations (a missing secret, a missing lock store, routes never mounted), opens the
// receivers, and starts the first-token check and, if asked, the sync on boot (never fatal).
func (p *Platform) open(ctx context.Context) error {
	p.recv.seal()
	have := p.recv.hasDispatch() || p.recv.hasJobs()
	if !p.client.Enabled() {
		if have {
			p.log.Warn("flowcatalyst: receivers are registered but the module is disabled; nothing is mounted")
		}
		return nil
	}
	if !p.s.rEnabled.Get(p.b) {
		if have {
			p.log.Warn(fmt.Sprintf("flowcatalyst: handlers are registered (dispatch %v, scheduled jobs %v) but %s is false; nothing is mounted, so the platform cannot deliver to them",
				p.recv.dispatchPatterns(), p.recv.jobCodes(), p.s.rEnabled.Setting().Key()))
		}
	} else if err := p.checkRegistrations(have); err != nil {
		return err
	}
	if p.s.rEnabled.Get(p.b) {
		if p.recv.hasDispatch() {
			p.log.Info(fmt.Sprintf("flowcatalyst: dispatch handlers for %v", p.recv.dispatchPatterns()))
		}
		if p.recv.hasJobs() {
			p.log.Info(fmt.Sprintf("flowcatalyst: scheduled-job handlers for %v", p.recv.jobCodes()))
		}
		p.ready.Store(true)
	}
	p.startBackground(have)
	if p.s.syncOnBoot.Get(p.b) {
		// the platform is not on the request path: a failed sync is logged, never fatal
		if _, err := p.Sync(ctx, SyncApply); err != nil {
			p.log.Error("flowcatalyst: sync on boot failed; the application continues. " + err.Error())
		}
	}
	return nil
}

func (p *Platform) checkRegistrations(have bool) error {
	var ps []fw.Problem
	problem := func(key, msg string) { ps = append(ps, fw.Problem{Module: ModuleID, Key: key, Message: msg}) }
	if have && !p.mounted {
		problem("", "receivers are registered but their routes were never mounted: pass Platform.Routes() to the HTTP server")
	}
	if p.s.rRequireSig.Get(p.b) {
		if p.recv.hasDispatch() {
			if _, ok := p.effective(p.s.rDispatchSecret, p.s.rSigningSecret); !ok {
				problem(p.s.rDispatchSecret.Setting().Key(), p.missingSecret("dispatch"))
			}
		}
		if p.recv.hasJobs() {
			if _, ok := p.effective(p.s.rJobsSecret, p.s.rSigningSecret); !ok {
				problem(p.s.rJobsSecret.Setting().Key(), p.missingSecret("scheduled-job"))
			}
		}
	}
	if excl := p.recv.exclusiveJobCodes(); len(excl) > 0 && p.defaultLock() == nil {
		problem("", fmt.Sprintf("scheduled-job handlers %v need the overlap lock, but no lock store is given: build the locks with a default store "+
			"(dblock.Store or redislock.Store across replicas), pass them with flowcatalyst.WithLocks, or register the handlers with JobOptions{AlwaysConcurrent: true}", excl))
	}
	if len(ps) > 0 {
		return &fw.StartupError{Problems: ps}
	}
	return nil
}

func (p *Platform) missingSecret(receiver string) string {
	return "required while " + receiver + " handlers are registered and " + p.s.rRequireSig.Setting().Key() +
		" is true (or set the shared " + p.s.rSigningSecret.Setting().Key() + ")"
}

// startBackground learns early, and in the log, whether the credentials work, and (with handlers)
// the service principal. It never blocks or fails start-up.
func (p *Platform) startBackground(handlers bool) {
	ctx := p.ctx
	p.bg.Go(func() {
		if err := p.client.VerifyCredentials(ctx); err != nil {
			p.log.Warn(fmt.Sprintf("flowcatalyst: platform not usable at start-up (%v); the application continues and the '%s' health check reports it", err, PlatformCheckName))
			return
		}
		p.log.Info("flowcatalyst: platform reachable, credentials accepted")
		if handlers {
			if id, err := p.client.ServicePrincipal(ctx); err != nil {
				p.log.Warn(fmt.Sprintf("flowcatalyst: cannot run receiver handlers yet: %v; deliveries are answered 503 until the platform token names the application's service principal", err))
			} else {
				p.log.Info(fmt.Sprintf("flowcatalyst: receiver handlers run as service principal %s", id))
			}
		}
	})
}

func (p *Platform) shutdown(context.Context) error {
	drain := p.b.ShutdownDrain()
	if p.runner != nil {
		// running jobs are cancelled and reported as failed; the platform client is still open
		p.runner.stop(drain)
	}
	done := make(chan struct{})
	go func() { p.inFlight.Wait(); p.bg.Wait(); close(done) }()
	t := time.NewTimer(drain)
	defer t.Stop()
	select {
	case <-done:
	case <-t.C:
		p.log.Warn("flowcatalyst: dispatch handlers still running after the drain; abandoned")
	}
	return nil
}
