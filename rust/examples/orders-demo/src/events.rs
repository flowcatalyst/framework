//! The events: the same types and JSON keys as the Java demo's records, so the outbox rows are
//! the same.

use fw_usecase::{DomainEvent, EventMetadata, EventType};
use serde::Serialize;

use crate::domain::{Cents, CustomerName, LineId, OrderId, Quantity, Sku};

/// `orders:sales:order:placed`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OrderPlaced {
    #[serde(skip)]
    pub(crate) meta: EventMetadata,
    /// The order.
    pub order_id: OrderId,
    /// The customer.
    pub customer: CustomerName,
}

impl DomainEvent for OrderPlaced {
    const TYPE: EventType = EventType::of("orders:sales:order:placed");
    fn metadata(&self) -> &EventMetadata {
        &self.meta
    }
}

/// `orders:sales:order:line-added`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OrderLineAdded {
    #[serde(skip)]
    pub(crate) meta: EventMetadata,
    /// The order.
    pub order_id: OrderId,
    /// The new line.
    pub line_id: LineId,
    /// Its SKU.
    pub sku: Sku,
    /// Its quantity.
    pub quantity: Quantity,
    /// Its unit price.
    pub unit_price_cents: Cents,
    /// The order's line count after it.
    pub line_count: u32,
}

impl DomainEvent for OrderLineAdded {
    const TYPE: EventType = EventType::of("orders:sales:order:line-added");
    fn metadata(&self) -> &EventMetadata {
        &self.meta
    }
}

/// `orders:sales:order:cancelled`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OrderCancelled {
    #[serde(skip)]
    pub(crate) meta: EventMetadata,
    /// The order.
    pub order_id: OrderId,
    /// Why.
    pub reason: String,
    /// The lines it had.
    pub line_count: u32,
}

impl DomainEvent for OrderCancelled {
    const TYPE: EventType = EventType::of("orders:sales:order:cancelled");
    fn metadata(&self) -> &EventMetadata {
        &self.meta
    }
}
