package main

import (
	"context"
	"errors"
	"log/slog"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
)

var errNoDatabase = errors.New("no database instance is configured")

// placed is the data of an order-placed event: the orders demo's OrderPlaced, as the outbox wrote
// it (camelCase members).
type placed struct {
	OrderID  string `json:"orderId"`
	Customer string `json:"customer"`
}

// register registers the demo's handlers: before Routes seals the registry.
func register(fc *flowcatalyst.Platform, db *database.DB) error {
	err := flowcatalyst.OnDispatch(fc.Receivers(), orders.Placed.String(),
		func(ctx context.Context, cx fw.Context, d flowcatalyst.Delivery[placed]) (flowcatalyst.DispatchResult, error) {
			// at-least-once delivery: the job id is the idempotency key, so the row and the record of
			// having made it commit together, and a redelivery is acknowledged without a second row
			principal, _ := cx.Principal()
			cause, _ := cx.Causation()
			res, err := flowcatalyst.Once(ctx, d.Meta, db, "record-placed", func(ctx context.Context, tx *database.Tx) (struct{}, error) {
				_, err := tx.Conn().Exec(ctx,
					"INSERT INTO received_events (job_id, event_type, order_id, principal, causation) VALUES ($1, $2, $3, $4, $5)",
					d.Meta.JobID, d.Meta.EventType, d.Data.OrderID, principal.String(), cause.String())
				return struct{}{}, err
			})
			if err != nil {
				return nil, err // a transient failure: the platform retries
			}
			slog.Info("order-placed delivery", "job", d.Meta.JobID, "order", d.Data.OrderID, "first_time", res.Ran(), "principal", principal.String(), "causation", cause.String())
			return flowcatalyst.Ack{}, nil
		})
	if err != nil {
		return err
	}
	return fc.Receivers().OnScheduledJob("orders-report", flowcatalyst.JobOptions{},
		func(ctx context.Context, cx fw.Context, f *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
			var n int
			if err := db.Pool().QueryRow(ctx, "SELECT count(*) FROM received_events").Scan(&n); err != nil {
				return nil, err
			}
			f.Log(flowcatalyst.LogInfo, "counting the recorded orders")
			return flowcatalyst.Success{Value: map[string]any{"recorded": n}}, nil
		})
}
