package io.flowcatalyst.framework.examples.ordersjte;

/**
 * What the list page renders.
 *
 * @param query the list state
 * @param listing the page of orders
 * @param form the create form's state
 */
public record OrdersView(Query query, OrderStore.Listing listing, NewOrderForm form) {}
