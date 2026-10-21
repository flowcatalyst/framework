package io.flowcatalyst.framework.objectstore.s3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A small in-process S3 (path-style GET, PUT, DELETE and HEAD bucket) so the driver is tested over a real HTTP wire. */
final class FakeS3 implements AutoCloseable {

  final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  final AtomicInteger failNext = new AtomicInteger();
  private final HttpServer server;
  private final String bucket;

  FakeS3(String bucket) throws IOException {
    this.bucket = bucket;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  URI endpoint() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void handle(HttpExchange x) throws IOException {
    try {
      String path = x.getRequestURI().getPath();
      if (failNext.get() > 0 && failNext.decrementAndGet() >= 0) {
        reply(x, 500, "<Error><Code>InternalError</Code><Message>boom</Message></Error>".getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (!path.equals("/" + bucket) && !path.startsWith("/" + bucket + "/")) {
        reply(x, 404, "<Error><Code>NoSuchBucket</Code><Message>no bucket</Message></Error>".getBytes(StandardCharsets.UTF_8));
        return;
      }
      String key = path.length() <= bucket.length() + 2 ? "" : path.substring(bucket.length() + 2);
      switch (x.getRequestMethod()) {
        case "HEAD" -> reply(x, 200, new byte[0]);
        case "PUT" -> {
          objects.put(key, body(x));
          x.getResponseHeaders().add("ETag", "\"fake\"");
          reply(x, 200, new byte[0]);
        }
        case "GET" -> {
          byte[] v = objects.get(key);
          if (v == null) {
            reply(x, 404, "<Error><Code>NoSuchKey</Code><Message>nope</Message></Error>".getBytes(StandardCharsets.UTF_8));
          } else {
            reply(x, 200, v);
          }
        }
        case "DELETE" -> {
          objects.remove(key);
          reply(x, 204, new byte[0]);
        }
        default -> reply(x, 405, new byte[0]);
      }
    } finally {
      x.close();
    }
  }

  /** The request body, decoding the SDK's aws-chunked framing if it used it. */
  private static byte[] body(HttpExchange x) throws IOException {
    byte[] raw;
    try (InputStream in = x.getRequestBody()) {
      raw = in.readAllBytes();
    }
    String sha = x.getRequestHeaders().getFirst("x-amz-content-sha256");
    if (sha == null || !sha.startsWith("STREAMING")) {
      return raw;
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int i = 0;
    while (i < raw.length) {
      int eol = indexOfCrlf(raw, i);
      String header = new String(raw, i, eol - i, StandardCharsets.US_ASCII);
      int size = Integer.parseInt(header.split(";")[0].trim(), 16);
      i = eol + 2;
      if (size == 0) {
        break;
      }
      out.write(raw, i, size);
      i += size + 2;
    }
    return out.toByteArray();
  }

  private static int indexOfCrlf(byte[] b, int from) {
    for (int i = from; i + 1 < b.length; i++) {
      if (b[i] == '\r' && b[i + 1] == '\n') {
        return i;
      }
    }
    throw new IllegalStateException("bad chunk framing");
  }

  private static void reply(HttpExchange x, int status, byte[] body) throws IOException {
    x.getResponseHeaders().add("Content-Type", status >= 400 ? "application/xml" : "application/octet-stream");
    x.sendResponseHeaders(status, status == 204 || body.length == 0 && status != 200 ? -1 : body.length == 0 ? -1 : body.length);
    if (body.length > 0 && status != 204) {
      x.getResponseBody().write(body);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
