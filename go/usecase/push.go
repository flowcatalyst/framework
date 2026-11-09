package usecase

import (
	"context"
	"errors"
	"fmt"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/pushrow"
)

// stepPush is the step code of a push that cannot be written (push contract section 2).
const stepPush = "PUSH"

// pushStep is a push to clients: a row of fw_push_outbox (or, with push.driver=platform, a PUSH row
// of outbox_messages) written by the runner, with the Context's client.
type pushStep struct {
	topic, typ, data string
	id, clientCode   string // platform driver only
	group            string // platform driver only: the outbox message group, "" for the topic
	ungrouped        bool   // platform driver only
	err              error  // the push was refused when it was built
}

// PushOption changes how a push is written. PushID and PushClientCode only exist on the platform
// driver's outbox row: with the hosted drivers (postgres, redis) a push that carries them fails the
// run as INTERNAL / INVALID_PUSH (reason OPTION_UNSUPPORTED) rather than lose them silently. The
// message group options are ordering hints of that row; the hosted drivers log in commit order
// whatever they are, so they have no effect there.
type PushOption func(*pushStep)

// PushID sets the push id, which is the outbox row id and the payload's pushId (1 to 26 characters
// [0-9A-Za-z_-]); without it a TSID is generated. Platform driver only.
func PushID(id string) PushOption { return func(p *pushStep) { p.id = id } }

// PushClientCode names the client the push is for by identifier, instead of the Context's client
// (the row's client_id stays the Context's). Platform driver only.
func PushClientCode(code string) PushOption { return func(p *pushStep) { p.clientCode = code } }

// PushMessageGroup sets the outbox message group of the push's row; the default is the topic, so
// the pushes of one topic reach the gateway in order.
func PushMessageGroup(group string) PushOption { return func(p *pushStep) { p.group = group } }

// PushUngrouped writes the row with no message group: the platform's outbox processor sends such
// rows in concurrent batches, which is faster and keeps no order. For signal-only topics.
func PushUngrouped() PushOption { return func(p *pushStep) { p.ungrouped = true } }

// Push adds a push to clients (push contract section 2): a small message to the browsers, apps and
// devices listening on topic, written to fw_push_outbox in the attempt's transaction, in plan order,
// for the Context's client. With push.driver=platform it is written as a PUSH row of the
// application's outbox instead (contract amendment 17.17), in the same transaction. It is sent only
// if the transaction commits; a failed attempt writes none.
// It is not an event: it is not audited, is never the result and does not count as the plan's
// event. data must marshal to a JSON object (a map, a struct, or JSON text as jsontext.Value or
// []byte), at most push.max-data-bytes (the runner's [WithPushMaxDataBytes]). A topic, type or data
// that breaks section 1 fails the run as INTERNAL / INVALID_PUSH, logged as a bug.
//
//	usecase.NewPlan(changed, usecase.Update(order, repo),
//		usecase.Push("fulfil:order:"+id, "order.updated", map[string]any{"orderId": id, "version": v}))
func Push(topic, typ string, data any, opts ...PushOption) Step {
	if err := pushrow.CheckTopic(topic); err != nil {
		return pushStep{err: err}
	}
	if err := pushrow.CheckType(typ); err != nil {
		return pushStep{err: err}
	}
	text, err := pushrow.Encode(data, pushrow.MaxDataBytes)
	if err != nil {
		return pushStep{err: err}
	}
	p := pushStep{topic: topic, typ: typ, data: text}
	for _, o := range opts {
		o(&p)
	}
	for _, check := range []func() error{
		func() error { return checkIf(p.id, pushrow.CheckID) },
		func() error { return checkIf(p.clientCode, pushrow.CheckClientCode) },
		func() error { return pushrow.CheckGroup(p.group) },
	} {
		if err := check(); err != nil {
			return pushStep{err: err}
		}
	}
	return p
}

func checkIf(v string, check func(string) error) error {
	if v == "" {
		return nil
	}
	return check(v)
}

// WithPushMaxDataBytes sets the largest push data the runner writes (push.max-data-bytes; default
// 16384, at most 65536). push.Pushes.RunnerOption passes the configured value.
func WithPushMaxDataBytes(n int) RunnerOption {
	return func(r *Runner) { r.pushMax = n }
}

// WithPlatformPushes makes the runner write a push as a PUSH row of the application's outbox,
// for the platform's push gateway (push.driver=platform), instead of a row of fw_push_outbox.
// push.Pushes.RunnerOption passes it when the module's driver is platform.
func WithPlatformPushes() RunnerOption {
	return func(r *Runner) { r.platformPush = true }
}

func (pushStep) step()                                  {}
func (pushStep) aggregate() (fw.ClientID, Entity, bool) { return fw.ClientID{}, Entity{}, false }

// apply is never called: the runner writes a push with the Context's client (writePush).
func (pushStep) apply(context.Context, *database.Tx) (bool, error) {
	return false, errors.New("usecase: a push step is written by the runner")
}

// checkPush is step 3 for a push: refused when it was built, or larger than the runner allows, or
// carrying what the runner's driver cannot write.
func (r *Runner) checkPush(p pushStep) error {
	if p.err != nil {
		return p.err
	}
	if limit := r.pushMax; len(p.data) > limit {
		return &pushrow.Error{Reason: pushrow.DataTooLarge, Message: fmt.Sprintf("the data is %d bytes, more than %d", len(p.data), limit)}
	}
	if !r.platformPush && (p.id != "" || p.clientCode != "") {
		return &pushrow.Error{Reason: pushrow.OptionUnsupported, Message: "PushID and PushClientCode need push.driver=platform"}
	}
	return nil
}

func (r *Runner) writePush(ctx context.Context, tx *database.Tx, cx fw.Context, p pushStep) error {
	if r.platformPush {
		return r.outbox.WritePushes(ctx, tx, PlatformPush{Context: cx, Step: p})
	}
	return pushrow.Insert(ctx, tx.Conn(), p.topic, p.typ, cx.Client(), p.data, r.clock.Now())
}
