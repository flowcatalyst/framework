package flowcatalyst

import (
	"embed"
	"io/fs"
	"net/url"
	"regexp"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

// ModuleID is the module's id, and the component it claims.
const ModuleID = "flowcatalyst"

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrations returns the module's migration set as embedded: V0001 creates fw_dispatch_once, the
// table behind [Once] (the Java module's file, byte for byte).
func Migrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // the directory is embedded above
	}
	return sub
}

func positive(d time.Duration) string {
	if d <= 0 {
		return "must be positive"
	}
	return ""
}

var pathPattern = regexp.MustCompile(`^(/[A-Za-z0-9._~-]+)+$`)

type settings struct {
	enabled       config.Handle[bool]
	baseURL       config.Handle[url.URL]
	application   config.Handle[string]
	clientID      config.Handle[string]
	clientSecret  config.Handle[config.Secret]
	timeout       config.Handle[time.Duration]
	targetBaseURL config.Handle[url.URL]
	syncOnBoot    config.Handle[bool]
	staleAfter    config.Handle[time.Duration]

	rEnabled        config.Handle[bool]
	rPath           config.Handle[string]
	rSigningSecret  config.Handle[config.Secret]
	rBearerToken    config.Handle[config.Secret]
	rDispatchSecret config.Handle[config.Secret]
	rDispatchBearer config.Handle[config.Secret]
	rJobsSecret     config.Handle[config.Secret]
	rJobsBearer     config.Handle[config.Secret]
	rRequireSig     config.Handle[bool]
	rDispatchTO     config.Handle[time.Duration]
	rJobTO          config.Handle[time.Duration]
	rLockLease      config.Handle[time.Duration]
	rRetryDelay     config.Handle[time.Duration]
	rOnceRetention  config.Handle[time.Duration]
	rOncePurge      config.Handle[time.Duration]
}

func declareSettings(d *fw.Declarer) settings {
	s := settings{}
	s.enabled = config.Declare(d, config.Bool("flowcatalyst.enabled",
		"Whether the FlowCatalyst module is active; when false no setting below is needed.").WithDefault(true))
	s.baseURL = config.Declare(d, config.URL("flowcatalyst.base-url", "Base URL of the FlowCatalyst platform. Required while enabled."))
	s.application = config.Declare(d, config.String("flowcatalyst.application",
		"This application's code on the platform, [a-z][a-z0-9-]*. Required while enabled."))
	s.clientID = config.Declare(d, config.String("flowcatalyst.client-id",
		"OAuth client id of the application's service account. Required while enabled."))
	s.clientSecret = config.Declare(d, config.SecretValue("flowcatalyst.client-secret",
		"OAuth client secret of the application's service account. Required while enabled."))
	s.timeout = config.Declare(d, config.Duration("flowcatalyst.timeout", "Timeout of requests to the platform.").
		WithDefault(30*time.Second).Checked(positive))
	s.targetBaseURL = config.Declare(d, config.URL("flowcatalyst.subscription-target-base-url",
		"Base URL that subscription targets given as a path (/webhooks/orders) resolve against."))
	s.syncOnBoot = config.Declare(d, config.Bool("flowcatalyst.sync.on-boot",
		"Sync definitions to the platform at start-up. Normally the deploy runs 'sync' instead.").WithDefault(false))
	s.staleAfter = config.Declare(d, config.Duration("flowcatalyst.outbox.stale-after",
		"The 'outbox' health check is down when the oldest undelivered outbox row is older than this.").
		WithDefault(5*time.Minute).Checked(positive))

	s.rEnabled = config.Declare(d, config.Bool("flowcatalyst.receivers.enabled",
		"Mount the receiver routes. False mounts neither /dispatch nor /scheduled-jobs even when handlers are registered "+
			"(start-up warns). With true, a receiver that has no handler answers 404.").WithDefault(true))
	s.rPath = config.Declare(d, config.String("flowcatalyst.receivers.path",
		"Path prefix of the receivers: {path}/dispatch and {path}/scheduled-jobs.").WithDefault("/fc").
		Checked(func(p string) string {
			if !pathPattern.MatchString(p) {
				return "must be a path of literal segments starting with '/' and without a trailing '/'"
			}
			return ""
		}))
	s.rSigningSecret = config.Declare(d, config.SecretValue("flowcatalyst.receivers.signing-secret",
		"The shared signing secret of the service account that signs deliveries to this application; used by each receiver "+
			"without its own. Required for a receiver with handlers when require-signature is true and it has no secret of its own."))
	s.rBearerToken = config.Declare(d, config.SecretValue("flowcatalyst.receivers.bearer-token",
		"Shared bearer token; when set, deliveries must also carry 'Authorization: Bearer <token>' (AND-ed with the signature)."))
	s.rDispatchSecret = config.Declare(d, config.SecretValue("flowcatalyst.receivers.dispatch.signing-secret",
		"Signing secret for deliveries to the dispatch receiver (the subscription's or connection's service account). "+
			"Falls back to flowcatalyst.receivers.signing-secret."))
	s.rDispatchBearer = config.Declare(d, config.SecretValue("flowcatalyst.receivers.dispatch.bearer-token",
		"Bearer token for the dispatch receiver. Falls back to flowcatalyst.receivers.bearer-token."))
	s.rJobsSecret = config.Declare(d, config.SecretValue("flowcatalyst.receivers.scheduled-jobs.signing-secret",
		"Signing secret for deliveries to the scheduled-job receiver (the application's service account). "+
			"Falls back to flowcatalyst.receivers.signing-secret."))
	s.rJobsBearer = config.Declare(d, config.SecretValue("flowcatalyst.receivers.scheduled-jobs.bearer-token",
		"Bearer token for the scheduled-job receiver. Falls back to flowcatalyst.receivers.bearer-token."))
	s.rRequireSig = config.Declare(d, config.Bool("flowcatalyst.receivers.require-signature",
		"Refuse unsigned deliveries. False accepts them (start-up logs a warning).").WithDefault(true))
	s.rDispatchTO = config.Declare(d, config.Duration("flowcatalyst.receivers.dispatch-timeout",
		"How long a dispatch-job handler may run before it is cancelled and the platform is answered 503. "+
			"Keep it below the job's timeout (platform default 30s).").WithDefault(25*time.Second).Checked(positive))
	s.rJobTO = config.Declare(d, config.Duration("flowcatalyst.receivers.job-timeout",
		"How long a scheduled job may run when its firing has no timeoutSeconds; then its context is cancelled "+
			"and a tracked job is reported as failed.").WithDefault(10*time.Minute).Checked(positive))
	s.rLockLease = config.Declare(d, config.Duration("flowcatalyst.receivers.job-lock-lease",
		"Lease of the scheduled-job overlap lock, refreshed every third of it while the job runs: how long a crashed run blocks the next one.").
		WithDefault(10*time.Minute).Checked(config.Between(time.Second, 24*time.Hour)))
	s.rRetryDelay = config.Declare(d, config.Duration("flowcatalyst.receivers.completion-retry-delay",
		"Wait before the first retry of a failed scheduled-job completion call; it doubles for each of the 3 retries.").
		WithDefault(time.Second).Checked(config.Between(time.Duration(0), time.Minute)))
	s.rOnceRetention = config.Declare(d, config.Duration("flowcatalyst.receivers.once-retention",
		"How long Once keeps its records; older rows are purged. Keep it longer than the platform can redeliver a job.").
		WithDefault(30*24*time.Hour).Checked(positive))
	s.rOncePurge = config.Declare(d, config.Duration("flowcatalyst.receivers.once-purge-interval",
		"How often the background purge of old fw_dispatch_once rows runs.").WithDefault(time.Hour).Checked(positive))
	return s
}

// Declared is the module's declaration, from [Declare].
type Declared struct {
	s   settings
	reg *registry
}

// Declare declares the module: it claims the component flowcatalyst, declares every
// flowcatalyst.* setting and contributes the migration set that creates fw_dispatch_once (module
// flowcatalyst). Definitions are contributed through [Declared.Definitions]; the sync command and
// the receivers are made by [Build].
func Declare(app *fw.App) *Declared {
	d := app.Module(ModuleID)
	d.ClaimComponent("flowcatalyst")
	d.Migrations(Migrations())
	return &Declared{s: declareSettings(d), reg: &registry{}}
}
