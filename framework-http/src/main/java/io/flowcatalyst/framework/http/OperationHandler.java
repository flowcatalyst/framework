package io.flowcatalyst.framework.http;

/**
 * Handles one {@link Operation}, bound with {@link Routes#bind}.
 *
 * @param <B> the body type ({@link NoBody} when the operation has none)
 * @param <R> the response type
 */
@FunctionalInterface
public interface OperationHandler<B, R> {

  /**
   * Handles a request whose permission, parameters and body were already checked.
   *
   * @param request the decoded request
   * @return the outcome; never null
   * @throws Exception any failure; {@link HttpException} maps to its envelope, anything else to
   *     {@code 500}
   */
  Outcome<R> handle(OperationRequest<B> request) throws Exception;
}
