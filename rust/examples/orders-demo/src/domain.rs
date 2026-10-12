//! The order aggregate: one type per state, so lines can only be added to an open order.

use fw_core::{Id, IdKind, IdPrefix};
use fw_usecase::{Aggregate, ErrorCode, UseCaseError};
use serde::Serialize;
use sqlx::encode::IsNull;
use sqlx::error::BoxDynError;
use sqlx::postgres::{PgArgumentBuffer, PgTypeInfo, PgValueRef};
use sqlx::{Decode, Encode, Postgres, Type};

impl IdKind for Order {
    const PREFIX: IdPrefix = IdPrefix::of("ord");
}

/// The kind of a line id.
#[derive(Debug)]
pub struct LineKind;

impl IdKind for LineKind {
    const PREFIX: IdPrefix = IdPrefix::of("lin");
}

/// `ord_` + 13 Crockford characters.
pub type OrderId = Id<Order>;
/// `lin_` + 13 Crockford characters; not an `OrderId`.
pub type LineId = Id<LineKind>;

/// A customer name: trimmed, not blank.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Type)]
#[sqlx(transparent)]
pub struct CustomerName(String);

impl CustomerName {
    /// Parses a name.
    ///
    /// # Errors
    ///
    /// `VALIDATION` / `CUSTOMER_REQUIRED` when blank.
    pub fn parse(raw: &str) -> Result<Self, UseCaseError> {
        let s = raw.trim();
        if s.is_empty() {
            return Err(UseCaseError::validation(
                ErrorCode::of("CUSTOMER_REQUIRED"),
                "A customer is required.",
            ));
        }
        Ok(Self(s.to_owned()))
    }

    /// The name.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// A SKU: not blank (the table checks at most 20 characters, the last line of defence).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Type)]
#[sqlx(transparent)]
pub struct Sku(String);

impl Sku {
    /// Parses a SKU.
    ///
    /// # Errors
    ///
    /// `VALIDATION` / `SKU_REQUIRED` when blank.
    pub fn parse(raw: &str) -> Result<Self, UseCaseError> {
        if raw.trim().is_empty() {
            return Err(UseCaseError::validation(
                ErrorCode::of("SKU_REQUIRED"),
                "A sku is required.",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    /// The text.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// A quantity, at least 1.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Type)]
#[sqlx(transparent)]
pub struct Quantity(i32);

impl Quantity {
    /// Parses a quantity.
    ///
    /// # Errors
    ///
    /// `VALIDATION` / `QUANTITY_INVALID` when not positive.
    pub fn parse(raw: i32) -> Result<Self, UseCaseError> {
        if raw <= 0 {
            return Err(UseCaseError::validation(
                ErrorCode::of("QUANTITY_INVALID"),
                "The quantity must be positive.",
            ));
        }
        Ok(Self(raw))
    }

    /// The number.
    #[must_use]
    pub fn get(self) -> i32 {
        self.0
    }
}

/// A price in cents, not negative.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Type)]
#[sqlx(transparent)]
pub struct Cents(i64);

impl Cents {
    /// Parses a price.
    ///
    /// # Errors
    ///
    /// `VALIDATION` / `PRICE_INVALID` when negative.
    pub fn parse(raw: i64) -> Result<Self, UseCaseError> {
        if raw < 0 {
            return Err(UseCaseError::validation(
                ErrorCode::of("PRICE_INVALID"),
                "The price must not be negative.",
            ));
        }
        Ok(Self(raw))
    }

    /// The number.
    #[must_use]
    pub fn get(self) -> i64 {
        self.0
    }
}

/// One line of an order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OrderLine {
    /// The line id.
    pub id: LineId,
    /// The SKU.
    pub sku: Sku,
    /// The quantity.
    pub quantity: Quantity,
    /// The unit price.
    pub unit_price: Cents,
}

/// An open order: lines can be added, it can be cancelled.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpenOrder {
    pub(crate) id: OrderId,
    pub(crate) customer: CustomerName,
    pub(crate) lines: Vec<OrderLine>,
}

/// A cancelled order: there is no `with_line` and no `cancel`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CancelledOrder {
    pub(crate) id: OrderId,
    pub(crate) customer: CustomerName,
    pub(crate) lines: Vec<OrderLine>,
}

/// The aggregate.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Order {
    /// Open.
    Open(OpenOrder),
    /// Cancelled.
    Cancelled(CancelledOrder),
}

impl OpenOrder {
    /// A new order with no lines.
    #[must_use]
    pub fn place(id: OrderId, customer: CustomerName) -> Self {
        Self {
            id,
            customer,
            lines: Vec::new(),
        }
    }

    /// The order with one more line.
    #[must_use]
    pub fn with_line(mut self, line: OrderLine) -> Self {
        self.lines.push(line);
        self
    }

    /// The order, cancelled.
    #[must_use]
    pub fn cancel(self) -> CancelledOrder {
        CancelledOrder {
            id: self.id,
            customer: self.customer,
            lines: self.lines,
        }
    }

    /// The lines.
    #[must_use]
    pub fn lines(&self) -> &[OrderLine] {
        &self.lines
    }
}

impl Order {
    /// The customer.
    #[must_use]
    pub fn customer(&self) -> &CustomerName {
        match self {
            Order::Open(o) => &o.customer,
            Order::Cancelled(c) => &c.customer,
        }
    }

    /// The lines.
    #[must_use]
    pub fn lines(&self) -> &[OrderLine] {
        match self {
            Order::Open(o) => &o.lines,
            Order::Cancelled(c) => &c.lines,
        }
    }

    /// The stored status.
    #[must_use]
    pub fn status(&self) -> OrderStatus {
        match self {
            Order::Open(_) => OrderStatus::Open,
            Order::Cancelled(_) => OrderStatus::Cancelled,
        }
    }
}

impl Aggregate for Order {
    type Id = OrderId;
    fn id(&self) -> &OrderId {
        match self {
            Order::Open(o) => &o.id,
            Order::Cancelled(c) => &c.id,
        }
    }
}

/// The `status` column: `OPEN` or `CANCELLED`, as the Java demo stores it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderStatus {
    /// `OPEN`.
    Open,
    /// `CANCELLED`.
    Cancelled,
}

impl OrderStatus {
    fn as_str(self) -> &'static str {
        match self {
            Self::Open => "OPEN",
            Self::Cancelled => "CANCELLED",
        }
    }
}

impl Type<Postgres> for OrderStatus {
    fn type_info() -> PgTypeInfo {
        <String as Type<Postgres>>::type_info()
    }
    fn compatible(ty: &PgTypeInfo) -> bool {
        <String as Type<Postgres>>::compatible(ty)
    }
}

impl Encode<'_, Postgres> for OrderStatus {
    fn encode_by_ref(&self, buf: &mut PgArgumentBuffer) -> Result<IsNull, BoxDynError> {
        <&str as Encode<'_, Postgres>>::encode(self.as_str(), buf)
    }
}

impl<'r> Decode<'r, Postgres> for OrderStatus {
    fn decode(value: PgValueRef<'r>) -> Result<Self, BoxDynError> {
        match <&str as Decode<'r, Postgres>>::decode(value)? {
            "OPEN" => Ok(Self::Open),
            "CANCELLED" => Ok(Self::Cancelled),
            other => Err(format!("unknown order status '{other}'").into()),
        }
    }
}
