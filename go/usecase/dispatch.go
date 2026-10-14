package usecase

import (
	"errors"
	"fmt"
	"net/url"
	"strings"
	"unicode/utf16"

	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// JobKind is a dispatch job's kind.
type JobKind string

// The kinds; the framework's default is TASK (a job the application creates itself).
const (
	JobKindEvent JobKind = "EVENT"
	JobKindTask  JobKind = "TASK"
)

// JobMode is a dispatch job's mode.
type JobMode string

// The modes.
const (
	JobModeImmediate    JobMode = "IMMEDIATE"
	JobModeNextOnError  JobMode = "NEXT_ON_ERROR"
	JobModeBlockOnError JobMode = "BLOCK_ON_ERROR"
)

// JobQueue is a dispatch job's queue.
type JobQueue string

// The queues.
const (
	JobQueueDefault      JobQueue = "DEFAULT"
	JobQueueHighPriority JobQueue = "HIGH_PRIORITY"
)

// DispatchJob is a job a plan writes to the outbox for the platform's
// /api/dispatch-jobs/batch (write-path contract section 7a). Build it with [NewDispatchJob],
// which checks every rule, so a row the platform would refuse terminally is never written.
type DispatchJob struct {
	id                 string
	kind               JobKind
	code               string
	source             string
	subject            string
	targetURL          string
	payload            string
	payloadContentType string
	dataOnly           bool
	externalID         string
	eventID            string
	platformClientID   string
	subscriptionID     string
	serviceAccountID   string
	dispatchPoolID     string
	messageGroup       string
	mode               JobMode
	sequence           *int
	timeoutSeconds     *int
	maxRetries         *int
	metadata           []metadataEntry
	queue              JobQueue
	descriptor         string
	payloadErr         error
}

type metadataEntry struct{ key, value string }

// JobOption sets an optional field of a dispatch job.
type JobOption func(*DispatchJob)

// JobID sets the job's id ([0-9A-Za-z_-]{1,17}); without it the writer generates a TSID. The id
// is in the payload, so a batch sent again creates a job with the same id.
func JobID(id string) JobOption { return func(j *DispatchJob) { j.id = id } }

// KindOf sets EVENT or TASK (default TASK).
func KindOf(k JobKind) JobOption { return func(j *DispatchJob) { j.kind = k } }

// Source sets the source (default the code's application segment).
func Source(s string) JobOption { return func(j *DispatchJob) { j.source = s } }

// Subject sets the subject.
func Subject(s string) JobOption { return func(j *DispatchJob) { j.subject = s } }

// TextPayload sets the payload text and its content type.
func TextPayload(text, contentType string) JobOption {
	return func(j *DispatchJob) { j.payload, j.payloadContentType = text, contentType }
}

// JSONPayload sets the payload to v as JSON (written as the Java framework's mapper writes it),
// content type application/json.
func JSONPayload(v any) JobOption {
	return func(j *DispatchJob) {
		b, err := javafmt.MarshalJSON(v)
		if err != nil {
			j.payloadErr = fmt.Errorf("dispatch job payload: %w", err)
			return
		}
		j.payload, j.payloadContentType = string(b), "application/json"
	}
}

// DataOnly sets dataOnly (default false: the platform envelope, which the receivers understand).
func DataOnly(v bool) JobOption { return func(j *DispatchJob) { j.dataOnly = v } }

// ExternalID sets the external id.
func ExternalID(s string) JobOption { return func(j *DispatchJob) { j.externalID = s } }

// EventID sets the platform event id.
func EventID(s string) JobOption { return func(j *DispatchJob) { j.eventID = s } }

// PlatformClientID sets the platform's client id (written as clientId), never derived from the
// Context: the framework's ClientID is an identifier the platform would store unresolved.
func PlatformClientID(s string) JobOption { return func(j *DispatchJob) { j.platformClientID = s } }

// SubscriptionID sets the subscription id.
func SubscriptionID(s string) JobOption { return func(j *DispatchJob) { j.subscriptionID = s } }

// ServiceAccountID sets the service account id.
func ServiceAccountID(s string) JobOption { return func(j *DispatchJob) { j.serviceAccountID = s } }

// DispatchPoolID sets the dispatch pool id.
func DispatchPoolID(s string) JobOption { return func(j *DispatchJob) { j.dispatchPoolID = s } }

// MessageGroup sets the message group (also the row's message_group).
func MessageGroup(s string) JobOption { return func(j *DispatchJob) { j.messageGroup = s } }

// Mode sets the mode.
func Mode(m JobMode) JobOption { return func(j *DispatchJob) { j.mode = m } }

// Sequence sets the sequence (at least 1).
func Sequence(n int) JobOption { return func(j *DispatchJob) { j.sequence = &n } }

// TimeoutSeconds sets the timeout (at least 1).
func TimeoutSeconds(n int) JobOption { return func(j *DispatchJob) { j.timeoutSeconds = &n } }

// MaxRetries sets the retry count (at least 1).
func MaxRetries(n int) JobOption { return func(j *DispatchJob) { j.maxRetries = &n } }

// Metadata adds a metadata entry; a key given again keeps its first position and takes the new
// value. Written as the platform's array of {"key","value"}, in insertion order.
func Metadata(key, value string) JobOption {
	return func(j *DispatchJob) {
		for i := range j.metadata {
			if j.metadata[i].key == key {
				j.metadata[i].value = value
				return
			}
		}
		j.metadata = append(j.metadata, metadataEntry{key: key, value: value})
	}
}

// Queue sets the queue.
func Queue(q JobQueue) JobOption { return func(j *DispatchJob) { j.queue = q } }

// Descriptor sets the descriptor.
func Descriptor(s string) JobOption { return func(j *DispatchJob) { j.descriptor = s } }

const (
	textMax     = 255
	metadataMax = 100
)

// NewDispatchJob builds and checks a job: code {application}:{rest} (application
// [a-z][a-z0-9-]*, at most 255 characters), targetURL an absolute http(s) URL with a host and no
// user info, and the rules of every option. The error texts are the Java builder's.
func NewDispatchJob(code, targetURL string, opts ...JobOption) (DispatchJob, error) {
	j := DispatchJob{kind: JobKindTask, code: code, targetURL: targetURL, payload: "{}", payloadContentType: "application/json"}
	for _, o := range opts {
		o(&j)
	}
	if j.payloadErr != nil {
		return DispatchJob{}, j.payloadErr
	}
	if j.source == "" {
		if app, _, ok := strings.Cut(code, ":"); ok {
			j.source = app
		}
	}
	if err := j.check(); err != nil {
		return DispatchJob{}, err
	}
	return j, nil
}

func (j *DispatchJob) check() error {
	if javaLen(j.code) > textMax || !validCode(j.code) {
		return fmt.Errorf("dispatch job code must be {application}:{rest} (application [a-z][a-z0-9-]*), at most %d characters: %s", textMax, j.code)
	}
	if !validTarget(j.targetURL) {
		return errors.New("dispatch job targetUrl must be an absolute http(s) URL with a host and no user info")
	}
	if err := text("source", j.source, true); err != nil {
		return err
	}
	for _, f := range []struct{ name, v string }{
		{"subject", j.subject}, {"externalId", j.externalID}, {"messageGroup", j.messageGroup}, {"descriptor", j.descriptor},
	} {
		if err := text(f.name, f.v, false); err != nil {
			return err
		}
	}
	if isBlank(j.payloadContentType) || javaLen(j.payloadContentType) > 100 {
		return errors.New("payloadContentType must be 1 to 100 characters")
	}
	for _, f := range []struct{ name, v string }{
		{"id", j.id},
		{"eventId", j.eventID},
		{"platformClientId", j.platformClientID},
		{"subscriptionId", j.subscriptionID},
		{"serviceAccountId", j.serviceAccountID},
		{"dispatchPoolId", j.dispatchPoolID},
	} {
		if f.v != "" && !validPlatformID(f.v) {
			return fmt.Errorf("%s must match [0-9A-Za-z_-]{1,17}", f.name)
		}
	}
	for _, f := range []struct {
		name string
		v    *int
	}{{"sequence", j.sequence}, {"timeoutSeconds", j.timeoutSeconds}, {"maxRetries", j.maxRetries}} {
		if f.v != nil && *f.v < 1 {
			return fmt.Errorf("%s must be at least 1 (the platform turns 0 into its default)", f.name)
		}
	}
	switch j.kind {
	case JobKindEvent, JobKindTask:
	default:
		return fmt.Errorf("dispatch job kind must be EVENT or TASK: %s", j.kind)
	}
	switch j.mode {
	case "", JobModeImmediate, JobModeNextOnError, JobModeBlockOnError:
	default:
		return fmt.Errorf("dispatch job mode must be IMMEDIATE, NEXT_ON_ERROR or BLOCK_ON_ERROR: %s", j.mode)
	}
	switch j.queue {
	case "", JobQueueDefault, JobQueueHighPriority:
	default:
		return fmt.Errorf("dispatch job queue must be DEFAULT or HIGH_PRIORITY: %s", j.queue)
	}
	if len(j.metadata) > metadataMax {
		return fmt.Errorf("at most %d metadata entries", metadataMax)
	}
	for _, e := range j.metadata {
		if isBlank(e.key) {
			return errors.New("metadata keys must not be blank")
		}
	}
	return nil
}

// text checks a required (or, when set, an optional) text field: 1 to 255 characters, not
// blank. An optional field's zero value means unset.
func text(name, v string, required bool) error {
	if !required && v == "" {
		return nil
	}
	if isBlank(v) || javaLen(v) > textMax {
		return fmt.Errorf("%s must be 1 to %d characters", name, textMax)
	}
	return nil
}

// javaLen is a string's length as Java counts it (UTF-16 code units).
func javaLen(s string) int { return len(utf16.Encode([]rune(s))) }

// isBlank is Java's String.isBlank: empty or only white space.
func isBlank(s string) bool { return strings.TrimFunc(s, isJavaWhitespace) == "" }

func isJavaWhitespace(r rune) bool {
	switch r {
	case ' ', ' ', ' ':
		return false
	case ' ', '\t', '\n', '\v', '\f', '\r', '\u001c', '\u001d', '\u001e', '\u001f':
		return true
	}
	return r == ' ' || (r >= ' ' && r <= ' ') || (r >= ' ' && r <= ' ') ||
		r == ' ' || r == ' ' || r == ' ' || r == '　'
}

// validCode matches [a-z][a-z0-9-]*:\S.* (Java's pattern: the character after ':' is not white
// space).
func validCode(s string) bool {
	app, rest, ok := strings.Cut(s, ":")
	if !ok || !isSlug(app) || rest == "" {
		return false
	}
	r := []rune(rest)
	if isJavaRegexSpace(r[0]) {
		return false
	}
	for _, c := range r[1:] {
		if c == '\n' || c == '\r' || c == '\u0085' || c == ' ' || c == ' ' {
			return false // '.' does not match line terminators
		}
	}
	return true
}

func isJavaRegexSpace(r rune) bool {
	return r == ' ' || r == '\t' || r == '\n' || r == '\v' || r == '\f' || r == '\r'
}

func validTarget(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || !u.IsAbs() {
		return false
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "http" && scheme != "https" {
		return false
	}
	return u.Hostname() != "" && u.User == nil
}

func validPlatformID(s string) bool {
	if len(s) < 1 || len(s) > 17 {
		return false
	}
	for i := range len(s) {
		c := s[i]
		ok := (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c == '-'
		if !ok {
			return false
		}
	}
	return true
}
