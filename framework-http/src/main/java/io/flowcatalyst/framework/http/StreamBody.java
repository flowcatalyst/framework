package io.flowcatalyst.framework.http;

import java.io.IOException;
import java.io.OutputStream;

/** A response body written by a callback, for streams such as Datastar's. */
@FunctionalInterface
public interface StreamBody {

  /**
   * Writes the body. Called once, by the server, after the status and headers are committed.
   *
   * @param out where to write; flush it to push data to the client
   * @throws IOException on a write failure
   * @throws ClientDisconnectedException when the client has gone away
   */
  void writeTo(OutputStream out) throws IOException;
}
