package io.flowcatalyst.framework.push;

/**
 * A topic a subscribe did not get, with why.
 *
 * @param topic the topic
 * @param code {@code TOPIC_FORBIDDEN} (no rule allows it and no ticket grants it) or
 *     {@code TICKET_INVALID} (a ticket that names it grants nothing)
 */
public record Denial(String topic, String code) {}
