package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.core.ServiceError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What an {@link OperationHandler} returns: the value, answered with the operation's success
 * status, or an expected failure, answered with the error envelope. Throwing {@link HttpException}
 * still works and maps as on any route.
 *
 * <pre>{@code
 * return switch (store.create(id, request.body())) {
 *   case TAKEN -> Outcome.failed(409, "CODE_TAKEN", "the client already has an ingestion with that code");
 *   case DONE -> Outcome.ok(store.find(id).orElseThrow()).header("Location", "/api/v1/ingestions/" + id);
 * };
 * }</pre>
 *
 * @param <R> the operation's response type
 */
public sealed interface Outcome<R> {

  /**
   * Success: the value and any extra response headers.
   *
   * @param <R> the response type
   * @param value the value written as the JSON body ({@link NoBody#VALUE} for {@code 204})
   * @param headers extra headers, added in order (several {@code Set-Cookie} are fine)
   */
  record Ok<R>(R value, List<Response.Header> headers) implements Outcome<R> {

    /**
     * Copies the headers.
     *
     * @param value the value
     * @param headers the headers
     */
    public Ok {
      Objects.requireNonNull(value, "an Ok outcome has a value; use NoBody.VALUE for none");
      headers = List.copyOf(headers);
    }

    /**
     * The same outcome with one more header.
     *
     * @param name the header name
     * @param value the header value
     * @return a new outcome
     */
    public Ok<R> header(String name, String value) {
      List<Response.Header> more = new ArrayList<>(headers);
      more.add(new Response.Header(name, value));
      return new Ok<>(this.value, more);
    }
  }

  /**
   * An expected failure, answered with the envelope of {@link HttpErrors#of(ServiceError)}. A
   * {@code UseCaseError} is a {@link ServiceError}, so a use case's failure passes straight
   * through.
   *
   * @param <R> the response type
   * @param error the error
   */
  record Failed<R>(ServiceError error) implements Outcome<R> {}

  /**
   * Success with a value.
   *
   * @param <R> the response type
   * @param value the value
   * @return the outcome
   */
  static <R> Ok<R> ok(R value) {
    return new Ok<>(value, List.of());
  }

  /**
   * Success with no body, for an operation answered {@code 204}.
   *
   * @return the outcome
   */
  static Ok<NoBody> noContent() {
    return new Ok<>(NoBody.VALUE, List.of());
  }

  /**
   * A failure from a service error, for example a use case's {@code UseCaseError}.
   *
   * @param <R> the response type
   * @param error the error
   * @return the outcome
   */
  static <R> Outcome<R> failed(ServiceError error) {
    return new Failed<>(error);
  }

  /**
   * A failure with a status, a code and a message.
   *
   * @param <R> the response type
   * @param status the HTTP status, 400 to 599
   * @param code the {@code error} code, for example {@code CODE_TAKEN}
   * @param message the message; never echo request values
   * @return the outcome
   */
  static <R> Outcome<R> failed(int status, String code, String message) {
    return failed(status, code, message, Map.of());
  }

  /**
   * A failure with details.
   *
   * @param <R> the response type
   * @param status the HTTP status, 400 to 599
   * @param code the {@code error} code
   * @param message the message
   * @param details the {@code details} map
   * @return the outcome
   */
  static <R> Outcome<R> failed(int status, String code, String message, Map<String, String> details) {
    if (status < 400 || status > 599) {
      throw new IllegalArgumentException("a failure status is 400 to 599, not " + status);
    }
    return new Failed<>(new SimpleError(status, code, message, Map.copyOf(details)));
  }

  /**
   * A plain {@link ServiceError}, built by {@link #failed(int, String, String, Map)}.
   *
   * @param httpStatus the status
   * @param code the {@code error} code
   * @param message the message
   * @param details the {@code details} map
   */
  record SimpleError(int httpStatus, String code, String message, Map<String, String> details)
      implements ServiceError {}
}
