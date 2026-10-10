package io.flowcatalyst.framework.httpclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** A local HTTP server (the JDK's) whose answers tests script per path, recording every request. */
final class FakeServer implements AutoCloseable {

  /** One recorded request. */
  record Recorded(String method, String rawPath, String rawQuery, Map<String, List<String>> headers, String body) {
    String header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) {
          return e.getValue().get(0);
        }
      }
      return null;
    }
  }

  /** An answer to send. */
  record Answer(int status, Map<String, String> headers, byte[] body, long delayMillis, boolean chunked) {
    static Answer of(int status, String body) {
      return new Answer(status, Map.of("Content-Type", "application/json"), body.getBytes(StandardCharsets.UTF_8), 0, false);
    }

    Answer with(String name, String value) {
      Map<String, String> h = new LinkedHashMap<>(headers);
      h.put(name, value);
      return new Answer(status, h, body, delayMillis, chunked);
    }

    Answer delayed(long millis) {
      return new Answer(status, headers, body, millis, chunked);
    }

    Answer streamed() {
      return new Answer(status, headers, body, delayMillis, true);
    }
  }

  /** Answers a request. */
  interface Script {
    Answer answer(Recorded request, int count);
  }

  private final HttpServer server;
  private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Script> scripts = Collections.synchronizedMap(new LinkedHashMap<>());
  private final Map<String, Integer> counts = Collections.synchronizedMap(new LinkedHashMap<>());

  private FakeServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", this::handle);
    server.start();
  }

  static FakeServer start() throws IOException {
    return new FakeServer();
  }

  URI base() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  void on(String path, Script script) {
    scripts.put(path, script);
  }

  void on(String path, Answer answer) {
    scripts.put(path, (r, n) -> answer);
  }

  List<Recorded> requests() {
    return List.copyOf(requests);
  }

  long count(String path) {
    return requests().stream().filter(r -> r.rawPath().equals(path)).count();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    URI uri = exchange.getRequestURI();
    Map<String, List<String>> headers = new LinkedHashMap<>();
    exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
    Recorded recorded = new Recorded(exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(), headers, body);
    requests.add(recorded);
    int n = counts.merge(uri.getRawPath(), 1, Integer::sum);
    Script script = scripts.get(uri.getRawPath());
    Answer answer = script == null ? Answer.of(404, "{\"error\":\"NOT_FOUND\"}") : script.answer(recorded, n);
    try {
      if (answer.delayMillis() > 0) {
        Thread.sleep(answer.delayMillis());
      }
      answer.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
      boolean noBody = exchange.getRequestMethod().equals("HEAD") || answer.status() == 204 || answer.status() == 304;
      long length = noBody ? -1 : answer.chunked() ? 0 : answer.body().length == 0 ? -1 : answer.body().length;
      exchange.sendResponseHeaders(answer.status(), length);
      if (!noBody && answer.body().length > 0) {
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(answer.body());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      // the client went away (a reset after a timeout or an oversized body)
    } finally {
      exchange.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
