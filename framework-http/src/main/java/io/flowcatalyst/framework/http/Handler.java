package io.flowcatalyst.framework.http;

/** Handles one request. */
@FunctionalInterface
public interface Handler {

  /**
   * Handles the request.
   *
   * @param request the request
   * @return the response
   * @throws Exception any failure; {@link HttpException} becomes its envelope, anything else a 500
   */
  Response handle(Request request) throws Exception;
}
