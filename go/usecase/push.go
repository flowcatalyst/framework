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

// pushStep is a push to clients: a row of fw_push_outbox written by the runner, with the
// Context's client.
type pushStep struct {
	topic, typ, data string
	err              error // the push was refused when it was built
}

// Push adds a push to clients (push contract section 2): a small message to the browsers, apps and
// devices listening on topic, written to fw_push_outbox in the attempt's transaction, in plan order,
// for the Context's client. It is sent only if the transaction commits; a failed attempt writes none.
// It is not an event: it is not audited, is never the result and does not count as the plan's
// event. data must marshal to a JSON object (a map, a struct, or JSON text as jsontext.Value or
// []byte), at most push.max-data-bytes (the runner's [WithPushMaxDataBytes]). A topic, type or data
// that breaks section 1 fails the run as INTERNAL / INVALID_PUSH, logged as a bug.
//
//	usecase.NewPlan(changed, usecase.Update(order, repo),
//		usecase.Push("fulfil:order:"+id, "order.updated", map[string]any{"orderId": id, "version": v}))
func Push(topic, typ string, data any) Step {
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
	return pushStep{topic: topic, typ: typ, data: text}
}

// WithPushMaxDataBytes sets the largest push data the runner writes (push.max-data-bytes; default
// 16384, at most 65536). push.Pushes.RunnerOption passes the configured value.
func WithPushMaxDataBytes(n int) RunnerOption {
	return func(r *Runner) { r.pushMax = n }
}

func (pushStep) step()                                  {}
func (pushStep) aggregate() (fw.ClientID, Entity, bool) { return fw.ClientID{}, Entity{}, false }

// apply is never called: the runner writes a push with the Context's client (writePush).
func (pushStep) apply(context.Context, *database.Tx) (bool, error) {
	return false, errors.New("usecase: a push step is written by the runner")
}

// checkPush is step 3 for a push: refused when it was built, or larger than the runner allows.
func (r *Runner) checkPush(p pushStep) error {
	if p.err != nil {
		return p.err
	}
	if limit := r.pushMax; len(p.data) > limit {
		return &pushrow.Error{Reason: pushrow.DataTooLarge, Message: fmt.Sprintf("the data is %d bytes, more than %d", len(p.data), limit)}
	}
	return nil
}

func (r *Runner) writePush(ctx context.Context, tx *database.Tx, cx fw.Context, p pushStep) error {
	return pushrow.Insert(ctx, tx.Conn(), p.topic, p.typ, cx.Client(), p.data, r.clock.Now())
}
