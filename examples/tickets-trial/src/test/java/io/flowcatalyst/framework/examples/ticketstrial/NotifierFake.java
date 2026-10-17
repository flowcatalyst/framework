package io.flowcatalyst.framework.examples.ticketstrial;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** The notification service the service calls, as a local server that records what it is sent. */
final class NotifierFake implements AutoCloseable {

  /** One request received. */
  record Received(String method, String path, Map<String, String> headers, String body) {}

  private final HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(202);

  private NotifierFake(HttpServer server) {
    this.server = server;
  }

  static NotifierFake start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      NotifierFake fake = new NotifierFake(server);
      server.createContext("/", fake::handle);
      server.start();
      return fake;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  List<Received> received() {
    return received;
  }

  /** What the service answers from now on. */
  void answer(int httpStatus) {
    status.set(httpStatus);
  }

  private void handle(HttpExchange e) throws IOException {
    byte[] body = e.getRequestBody().readAllBytes();
    Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    e.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
    received.add(new Received(e.getRequestMethod(), e.getRequestURI().getPath(), headers, new String(body, StandardCharsets.UTF_8)));
    byte[] reply = "{}".getBytes(StandardCharsets.UTF_8);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status.get(), reply.length);
    e.getResponseBody().write(reply);
    e.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
