package io.flowcatalyst.framework.push;

import static org.assertj.core.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** An open stream read frame by frame: a frame is the lines up to the blank line, with their terminators. */
final class Sse implements AutoCloseable {

  static final String EOF = "<EOF>";

  final HttpResponse<InputStream> response;
  private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
  private final Thread reader;

  private Sse(HttpResponse<InputStream> response) {
    this.response = response;
    this.reader = Thread.ofVirtual().name("test-sse").start(this::read);
  }

  /** Opens GET url; lastEventId, when not null, is sent as the Last-Event-ID header. */
  static Sse open(HttpClient http, String url, @Nullable String lastEventId) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
    if (lastEventId != null) {
      request.header("Last-Event-ID", lastEventId);
    }
    HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
      throw new IllegalStateException("listen: " + response.statusCode() + " " + body);
    }
    return new Sse(response);
  }

  private void read() {
    try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      StringBuilder frame = new StringBuilder();
      String line;
      while ((line = in.readLine()) != null) {
        frame.append(line).append('\n');
        if (line.isEmpty()) {
          frames.add(frame.toString());
          frame.setLength(0);
        }
      }
    } catch (IOException e) {
      // the stream was closed
    } finally {
      frames.add(EOF);
    }
  }

  /** The next frame, failing the test after ten seconds. */
  String next() {
    String f = poll(Duration.ofSeconds(10));
    if (f == null) {
      return fail("no frame within 10 s");
    }
    return f;
  }

  @Nullable String poll(Duration wait) {
    try {
      return frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /** The retry field and the subscribed event, which every stream starts with. */
  void greeting() {
    String retry = next();
    if (!retry.equals("retry: 3000\n\n")) {
      fail("the stream starts with %s", retry);
    }
    String subscribed = next();
    if (!subscribed.startsWith("event: subscribed\n")) {
      fail("then subscribed, not %s", subscribed);
    }
  }

  private static final Pattern ID = Pattern.compile("^id: (\\d+)\n", Pattern.MULTILINE);

  static long idOf(String frame) {
    Matcher m = ID.matcher(frame);
    if (!m.find()) {
      return fail("no id in %s", frame);
    }
    return Long.parseLong(m.group(1));
  }

  /** The ids of the next n push frames. */
  List<Long> ids(int n) {
    List<Long> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(idOf(next()));
    }
    return out;
  }

  /** Whether the stream ends within the wait (skipping nothing: the next frame must be the end). */
  boolean ends(Duration wait) {
    String f = poll(wait);
    return EOF.equals(f);
  }

  @Override
  public void close() {
    try {
      response.body().close();
    } catch (IOException e) {
      // already closed
    }
    reader.interrupt();
  }
}
