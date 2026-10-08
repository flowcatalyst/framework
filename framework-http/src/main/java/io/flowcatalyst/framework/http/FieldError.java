package io.flowcatalyst.framework.http;

/**
 * One failed field of a request body or parameter.
 *
 * @param field the field path, for example {@code quantity}, {@code address.street}, {@code tags[2]}
 * @param code an {@code UPPER_SNAKE} code for the rule that failed
 * @param message a message safe to show a caller; never contains the offending value
 */
public record FieldError(String field, String code, String message) {}
