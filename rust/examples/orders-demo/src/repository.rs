//! The order repository and a read model, in checked SQL: every query is checked against the
//! migrated schema when the crate compiles (from the committed `.sqlx/`, no database needed).

use std::fmt::Display;

use fw_core::ClientId;
use fw_db::{Db, DbError, Tx};
use fw_usecase::{Aggregate, Loaded, Repository, Version, VersionCheck};

use crate::domain::{
    CancelledOrder, Cents, CustomerName, LineId, OpenOrder, Order, OrderId, OrderLine, OrderStatus,
    Quantity, Sku,
};

/// Stores orders and their lines (the lines are rewritten with the order, covered by its version).
#[derive(Debug, Default, Clone, Copy)]
pub struct OrderRepository;

fn corrupt(e: impl Display) -> DbError {
    DbError::Decode(format!("corrupt order row: {e}").into())
}

async fn write_lines(tx: &mut Tx, order: &Order) -> Result<(), DbError> {
    for (position, line) in order.lines().iter().enumerate() {
        let position = i32::try_from(position).map_err(corrupt)?;
        sqlx::query!(
            "INSERT INTO order_lines (order_id, position, line_id, sku, quantity, unit_price_cents)
             VALUES ($1, $2, $3, $4, $5, $6)",
            order.id() as &OrderId,
            position,
            &line.id as &LineId,
            &line.sku as &Sku,
            line.quantity as Quantity,
            line.unit_price as Cents,
        )
        .execute(tx.conn())
        .await?;
    }
    Ok(())
}

impl Repository<Order> for OrderRepository {
    async fn find(
        &self,
        db: &Db,
        client: &ClientId,
        id: &OrderId,
    ) -> Result<Option<Loaded<Order>>, DbError> {
        let Some(row) = sqlx::query!(
            r#"SELECT client_id AS "client_id: ClientId", version, customer AS "customer: CustomerName",
                      status AS "status: OrderStatus"
                 FROM orders
                WHERE id = $1 AND client_id = $2"#,
            id as &OrderId,
            client as &ClientId,
        )
        .fetch_optional(db.pool())
        .await?
        else {
            return Ok(None);
        };
        let lines = sqlx::query_as!(
            OrderLine,
            r#"SELECT line_id AS "id: LineId", sku AS "sku: Sku", quantity AS "quantity: Quantity",
                      unit_price_cents AS "unit_price: Cents"
                 FROM order_lines WHERE order_id = $1 ORDER BY position"#,
            id as &OrderId,
        )
        .fetch_all(db.pool())
        .await?;
        let order = match row.status {
            OrderStatus::Open => Order::Open(OpenOrder {
                id: id.clone(),
                customer: row.customer,
                lines,
            }),
            OrderStatus::Cancelled => Order::Cancelled(CancelledOrder {
                id: id.clone(),
                customer: row.customer,
                lines,
            }),
        };
        Ok(Some(Loaded::new(
            row.client_id,
            Version::stored(row.version).map_err(corrupt)?,
            order,
        )))
    }

    async fn insert(&self, tx: &mut Tx, client: &ClientId, order: &Order) -> Result<(), DbError> {
        sqlx::query!(
            "INSERT INTO orders (id, client_id, version, customer, status) VALUES ($1, $2, 1, $3, $4)",
            order.id() as &OrderId,
            client as &ClientId,
            order.customer() as &CustomerName,
            order.status() as OrderStatus,
        )
        .execute(tx.conn())
        .await?;
        write_lines(tx, order).await
    }

    async fn update(
        &self,
        tx: &mut Tx,
        client: &ClientId,
        order: &Order,
        v: VersionCheck,
    ) -> Result<u64, DbError> {
        let rows = sqlx::query!(
            "UPDATE orders SET version = $1, customer = $2, status = $3
              WHERE id = $4 AND client_id = $5 AND version = $6",
            v.next.get(),
            order.customer() as &CustomerName,
            order.status() as OrderStatus,
            order.id() as &OrderId,
            client as &ClientId,
            v.expected.get(),
        )
        .execute(tx.conn())
        .await?
        .rows_affected();
        if rows == 1 {
            sqlx::query!(
                "DELETE FROM order_lines WHERE order_id = $1",
                order.id() as &OrderId
            )
            .execute(tx.conn())
            .await?;
            write_lines(tx, order).await?;
        }
        Ok(rows)
    }

    async fn delete(
        &self,
        tx: &mut Tx,
        client: &ClientId,
        order: &Order,
        v: VersionCheck,
    ) -> Result<u64, DbError> {
        // The lines go with the order: ON DELETE CASCADE.
        Ok(sqlx::query!(
            "DELETE FROM orders WHERE id = $1 AND client_id = $2 AND version = $3",
            order.id() as &OrderId,
            client as &ClientId,
            v.expected.get(),
        )
        .execute(tx.conn())
        .await?
        .rows_affected())
    }
}

/// One row of [`summaries`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OrderSummary {
    /// The order.
    pub id: OrderId,
    /// The customer.
    pub customer: CustomerName,
    /// The status.
    pub status: OrderStatus,
    /// The version.
    pub version: i64,
    /// How many lines.
    pub line_count: i64,
    /// The total.
    pub total_cents: i64,
    /// The smallest SKU, none for an order without lines.
    pub first_sku: Option<String>,
}

/// One row per order of a client, with its line count and total: a read model, not an aggregate.
/// The `LEFT JOIN` makes every column nullable to sqlx, so the ones that cannot be null say so
/// with `!` (checked-sql contract section 2).
///
/// # Errors
///
/// On a database error.
pub async fn summaries(db: &Db, client: &ClientId) -> Result<Vec<OrderSummary>, DbError> {
    sqlx::query_as!(
        OrderSummary,
        r#"SELECT o.id AS "id!: OrderId", o.customer AS "customer!: CustomerName", o.status AS "status!: OrderStatus",
                  o.version AS "version!",
                  count(l.position) AS "line_count!",
                  coalesce(sum(l.quantity * l.unit_price_cents), 0)::bigint AS "total_cents!",
                  min(l.sku) AS first_sku
             FROM orders o LEFT JOIN order_lines l ON l.order_id = o.id
            WHERE o.client_id = $1
            GROUP BY o.id
            ORDER BY o.id"#,
        client as &ClientId,
    )
    .fetch_all(db.pool())
    .await
}
