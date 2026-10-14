// Package dbtx holds the transaction type of package database. It is internal so that only the
// framework (the use-case runner, a receiver's once) can begin one: applications see the type as
// database.Tx and use its connection, but cannot make one.
package dbtx

import "github.com/jackc/pgx/v5"

// Tx is a transaction the framework began. Application code uses Conn inside a plan step; it
// never begins, commits or rolls back.
type Tx struct {
	tx pgx.Tx
}

// New wraps a transaction. Framework use only.
func New(tx pgx.Tx) *Tx { return &Tx{tx: tx} }

// Conn returns the transaction, which sqlc's generated code accepts as its DBTX
// (dbq.New(tx.Conn())).
func (t *Tx) Conn() pgx.Tx { return t.tx }
