//! The three use cases: place-order, add-line, cancel-order (the Java demo's names, rules, codes
//! and messages). Commands hold domain values, parsed at the edge, so `validate` has nothing left
//! to check.

use std::time::Duration;

use fw_core::{Context, Ids};
use fw_db::Db;
use fw_usecase::{
    AuditData, Authorized, ConflictPolicy, ErrorCode, Events, Fresh, Grant, Plan, Repository,
    UseCase, UseCaseError, UseCaseName, Valid,
};

use crate::domain::{
    Cents, CustomerName, LineKind, OpenOrder, Order, OrderId, OrderLine, Quantity, Sku,
};
use crate::events::{OrderCancelled, OrderLineAdded, OrderPlaced};
use crate::repository::OrderRepository;

const PRINCIPAL_REQUIRED: ErrorCode = ErrorCode::of("PRINCIPAL_REQUIRED");
const ORDER_NOT_FOUND: ErrorCode = ErrorCode::of("ORDER_NOT_FOUND");
const ORDER_NOT_OPEN: ErrorCode = ErrorCode::of("ORDER_NOT_OPEN");

/// Every use case here needs a signed-in principal (the Java demo's check; permissions arrive
/// with sessions, milestone R6).
fn principal_required(ctx: &Context) -> Result<Grant, UseCaseError> {
    match ctx.principal() {
        Some(_) => Ok(Grant::allowed()),
        None => Err(UseCaseError::authorization(
            PRINCIPAL_REQUIRED,
            "Sign in to change orders.",
        )),
    }
}

fn not_found() -> UseCaseError {
    UseCaseError::not_found(ORDER_NOT_FOUND, "There is no such order.")
}

fn not_open() -> UseCaseError {
    UseCaseError::business_rule(ORDER_NOT_OPEN, "The order is cancelled and cannot change.")
}

/// Places an order.
#[derive(Debug, Clone)]
pub struct PlaceOrder {
    /// The customer.
    pub customer: CustomerName,
}

impl PlaceOrder {
    /// Parses the input.
    ///
    /// # Errors
    ///
    /// `CUSTOMER_REQUIRED`.
    pub fn parse(customer: &str) -> Result<Self, UseCaseError> {
        Ok(Self {
            customer: CustomerName::parse(customer)?,
        })
    }
}

/// Adds a line to an open order.
#[derive(Debug, Clone)]
pub struct AddLine {
    /// The order.
    pub order: OrderId,
    /// The SKU.
    pub sku: Sku,
    /// The quantity.
    pub quantity: Quantity,
    /// The unit price.
    pub unit_price: Cents,
}

impl AddLine {
    /// Parses the input.
    ///
    /// # Errors
    ///
    /// `SKU_REQUIRED`, `QUANTITY_INVALID`, `PRICE_INVALID`.
    pub fn parse(
        order: OrderId,
        sku: &str,
        quantity: i32,
        unit_price_cents: i64,
    ) -> Result<Self, UseCaseError> {
        Ok(Self {
            order,
            sku: Sku::parse(sku)?,
            quantity: Quantity::parse(quantity)?,
            unit_price: Cents::parse(unit_price_cents)?,
        })
    }
}

/// Cancels an open order.
#[derive(Debug, Clone)]
pub struct CancelOrder {
    /// The order.
    pub order: OrderId,
    /// Why (not blank).
    pub reason: String,
}

impl CancelOrder {
    /// Parses the input.
    ///
    /// # Errors
    ///
    /// `REASON_REQUIRED`.
    pub fn parse(order: OrderId, reason: &str) -> Result<Self, UseCaseError> {
        if reason.trim().is_empty() {
            return Err(UseCaseError::validation(
                ErrorCode::of("REASON_REQUIRED"),
                "A reason is required.",
            ));
        }
        Ok(Self {
            order,
            reason: reason.to_owned(),
        })
    }
}

/// The conflict policies of the demo's use cases (the Java `Policies`).
#[derive(Debug, Clone, Copy)]
pub struct Policies {
    /// `add-line`: retry 3 times, 25 ms, by default.
    pub add_line: ConflictPolicy,
    /// `cancel-order`: fail, by default.
    pub cancel: ConflictPolicy,
}

impl Default for Policies {
    fn default() -> Self {
        Self {
            add_line: ConflictPolicy::retry(3, Duration::from_millis(25)),
            cancel: ConflictPolicy::Fail,
        }
    }
}

/// `place-order`.
#[derive(Debug, Clone)]
pub struct PlaceOrderUseCase {
    repo: OrderRepository,
    ids: Ids,
    events: Events,
}

impl UseCase for PlaceOrderUseCase {
    const NAME: UseCaseName = UseCaseName::of("place-order");
    type Command = PlaceOrder;
    type Event = OrderPlaced;

    async fn authorize(
        &self,
        ctx: &Context,
        _cmd: Valid<'_, PlaceOrder>,
    ) -> Result<Grant, UseCaseError> {
        principal_required(ctx)
    }

    async fn execute<'a>(
        &'a self,
        ctx: &'a Context,
        cmd: Authorized<'a, PlaceOrder>,
        _db: &'a Db,
    ) -> Result<Plan<'a, OrderPlaced>, UseCaseError> {
        let id: OrderId = self.ids.next();
        let order = Fresh::new(
            ctx,
            Order::Open(OpenOrder::place(id.clone(), cmd.customer.clone())),
        );
        let event = OrderPlaced {
            meta: self.events.metadata::<OrderPlaced>(ctx, id.as_str()),
            order_id: id,
            customer: cmd.customer.clone(),
        };
        Ok(Plan::save(order, &self.repo, event))
    }

    fn audit_data(&self, cmd: &PlaceOrder) -> Option<AuditData> {
        Some(AuditData::new().with("customer", cmd.customer.as_str()))
    }
}

/// `add-line`.
#[derive(Debug, Clone)]
pub struct AddLineUseCase {
    repo: OrderRepository,
    ids: Ids,
    events: Events,
    policy: ConflictPolicy,
}

impl UseCase for AddLineUseCase {
    const NAME: UseCaseName = UseCaseName::of("add-line");
    type Command = AddLine;
    type Event = OrderLineAdded;

    fn conflict_policy(&self) -> ConflictPolicy {
        self.policy
    }

    async fn authorize(
        &self,
        ctx: &Context,
        _cmd: Valid<'_, AddLine>,
    ) -> Result<Grant, UseCaseError> {
        principal_required(ctx)
    }

    async fn execute<'a>(
        &'a self,
        ctx: &'a Context,
        cmd: Authorized<'a, AddLine>,
        db: &'a Db,
    ) -> Result<Plan<'a, OrderLineAdded>, UseCaseError> {
        let order = self
            .repo
            .find(db, ctx.client(), &cmd.order)
            .await?
            .ok_or_else(not_found)?;
        let line = OrderLine {
            id: self.ids.next::<LineKind>(),
            sku: cmd.sku.clone(),
            quantity: cmd.quantity,
            unit_price: cmd.unit_price,
        };
        let (order, line_count) = order.try_map(|o| match o {
            Order::Open(open) => {
                let open = open.with_line(line.clone());
                let count = open.lines().len();
                Ok((Order::Open(open), count))
            }
            Order::Cancelled(_) => Err(not_open()),
        })?;
        let event = OrderLineAdded {
            meta: self
                .events
                .metadata::<OrderLineAdded>(ctx, cmd.order.as_str()),
            order_id: cmd.order.clone(),
            line_id: line.id,
            sku: line.sku,
            quantity: line.quantity,
            unit_price_cents: line.unit_price,
            line_count: u32::try_from(line_count).map_err(UseCaseError::failure)?,
        };
        Ok(Plan::save(order, &self.repo, event))
    }

    fn audit_data(&self, cmd: &AddLine) -> Option<AuditData> {
        Some(
            AuditData::new()
                .with("orderId", cmd.order.as_str())
                .with("sku", cmd.sku.as_str())
                .with("quantity", cmd.quantity.get()),
        )
    }
}

/// `cancel-order`.
#[derive(Debug, Clone)]
pub struct CancelOrderUseCase {
    repo: OrderRepository,
    events: Events,
    policy: ConflictPolicy,
}

impl UseCase for CancelOrderUseCase {
    const NAME: UseCaseName = UseCaseName::of("cancel-order");
    type Command = CancelOrder;
    type Event = OrderCancelled;

    fn conflict_policy(&self) -> ConflictPolicy {
        self.policy
    }

    async fn authorize(
        &self,
        ctx: &Context,
        _cmd: Valid<'_, CancelOrder>,
    ) -> Result<Grant, UseCaseError> {
        principal_required(ctx)
    }

    async fn execute<'a>(
        &'a self,
        ctx: &'a Context,
        cmd: Authorized<'a, CancelOrder>,
        db: &'a Db,
    ) -> Result<Plan<'a, OrderCancelled>, UseCaseError> {
        let order = self
            .repo
            .find(db, ctx.client(), &cmd.order)
            .await?
            .ok_or_else(not_found)?;
        let (order, line_count) = order.try_map(|o| match o {
            Order::Open(open) => {
                let count = open.lines().len();
                Ok((Order::Cancelled(open.cancel()), count))
            }
            Order::Cancelled(_) => Err(not_open()),
        })?;
        let event = OrderCancelled {
            meta: self
                .events
                .metadata::<OrderCancelled>(ctx, cmd.order.as_str()),
            order_id: cmd.order.clone(),
            reason: cmd.reason.clone(),
            line_count: u32::try_from(line_count).map_err(UseCaseError::failure)?,
        };
        Ok(Plan::save(order, &self.repo, event))
    }

    fn audit_data(&self, cmd: &CancelOrder) -> Option<AuditData> {
        Some(
            AuditData::new()
                .with("orderId", cmd.order.as_str())
                .with("reason", cmd.reason.as_str()),
        )
    }
}

/// The demo's use cases, built once.
#[derive(Debug, Clone)]
pub struct OrderUseCases {
    /// `place-order`.
    pub place_order: PlaceOrderUseCase,
    /// `add-line`.
    pub add_line: AddLineUseCase,
    /// `cancel-order`.
    pub cancel_order: CancelOrderUseCase,
}

impl OrderUseCases {
    /// The use cases with their policies.
    #[must_use]
    pub fn new(ids: Ids, events: Events, policies: Policies) -> Self {
        let repo = OrderRepository;
        Self {
            place_order: PlaceOrderUseCase {
                repo,
                ids: ids.clone(),
                events: events.clone(),
            },
            add_line: AddLineUseCase {
                repo,
                ids,
                events: events.clone(),
                policy: policies.add_line,
            },
            cancel_order: CancelOrderUseCase {
                repo,
                events,
                policy: policies.cancel,
            },
        }
    }
}
