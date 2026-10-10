package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.CorrelationId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;

/**
 * A request as handlers see it: no server types. Created by a server adapter with {@link #of};
 * the router adds path parameters, the correlation id and the context.
 *
 * <p>Not thread-safe: a request belongs to the thread handling it.
 */
public final class Request {

  private static final Pattern VALID_CORRELATION = Pattern.compile("[A-Za-z0-9._-]{1,100}");

  /**
   * A typed key for {@link #attribute(Key)}, so middleware can pass values to handlers.
   *
   * @param <T> the value type
   */
  public static final class Key<T> {
    private final String name;
    private final Class<T> type;

    private Key(String name, Class<T> type) {
      this.name = name;
      this.type = type;
    }

    /**
     * Creates a key. Keys are compared by identity: keep it in a constant.
     *
     * @param <T> the value type
     * @param name a name for diagnostics
     * @param type the value type
     * @return a new key
     */
    public static <T> Key<T> of(String name, Class<T> type) {
      return new Key<>(name, type);
    }

    @Override
    public String toString() {
      return "Key[" + name + "]";
    }
  }

  private final RawRequest raw;
  private final CorrelationId correlationId;
  private final Map<Key<?>, Object> attributes = new HashMap<>();

  private @Nullable Map<String, List<String>> query;
  private @Nullable Map<String, String> pathParams;
  private @Nullable String routePattern;
  private @Nullable Context context;
  private byte @Nullable [] cachedBody;
  private boolean streamTaken;

  private Request(RawRequest raw) {
    this.raw = raw;
    this.correlationId = resolveCorrelationId(raw.headers().get("x-correlation-id"));
  }

  /**
   * Creates a request from what a server adapter read.
   *
   * @param raw the raw request
   * @return the request, with a correlation id taken from {@code X-Correlation-Id} when valid and
   *     generated otherwise
   */
  public static Request of(RawRequest raw) {
    return new Request(raw);
  }

  private static CorrelationId resolveCorrelationId(@Nullable List<String> supplied) {
    if (supplied != null && !supplied.isEmpty() && VALID_CORRELATION.matcher(supplied.getFirst()).matches()) {
      return new CorrelationId(supplied.getFirst());
    }
    return CorrelationId.generate();
  }

  // ---- line and headers --------------------------------------------------------------------

  /**
   * The method.
   *
   * @return the method
   */
  public Method method() {
    return raw.method();
  }

  /**
   * The path as sent (percent-encoded, without the query).
   *
   * @return the path
   */
  public String path() {
    return raw.rawPath();
  }

  /**
   * The protocol.
   *
   * @return for example {@code HTTP/1.1}
   */
  public String protocol() {
    return raw.protocol();
  }

  /**
   * The IP address of the connection's peer, as the server saw it: a proxy's address when the
   * application runs behind one. Never read from a header (see {@code KeyResolvers.forwardedFor}
   * for that, with its trust rule).
   *
   * @return the address literal, or null when the server adapter does not know it
   */
  public @Nullable String remoteAddress() {
    return raw.remoteAddress();
  }

  /**
   * The first value of a header.
   *
   * @param name the name, case-insensitive
   * @return the value, or null
   */
  public @Nullable String header(String name) {
    List<String> values = raw.headers().get(name.toLowerCase(Locale.ROOT));
    return values == null || values.isEmpty() ? null : values.getFirst();
  }

  /**
   * Every value of a header.
   *
   * @param name the name, case-insensitive
   * @return the values, possibly empty
   */
  public List<String> headers(String name) {
    List<String> values = raw.headers().get(name.toLowerCase(Locale.ROOT));
    return values == null ? List.of() : values;
  }

  /**
   * The correlation id: the valid {@code X-Correlation-Id} request header, else generated.
   *
   * @return the id
   */
  public CorrelationId correlationId() {
    return correlationId;
  }

  // ---- path --------------------------------------------------------------------------------

  /**
   * The value of a path parameter, percent-decoded.
   *
   * @param name a parameter declared in the matched route's pattern
   * @return the value
   * @throws IllegalArgumentException when the route declares no such parameter
   */
  public String pathParam(String name) {
    Map<String, String> params = pathParams;
    String value = params == null ? null : params.get(name);
    if (value == null) {
      throw new IllegalArgumentException("the route declares no path parameter '" + name + "'");
    }
    return value;
  }

  /**
   * A path parameter as an int.
   *
   * @param name the parameter
   * @return the value
   * @throws ValidationException when it is not an int
   */
  public int pathInt(String name) {
    String v = pathParam(name);
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw ValidationException.of(name, "INVALID", "must be an integer");
    }
  }

  /**
   * A path parameter as a long.
   *
   * @param name the parameter
   * @return the value
   * @throws ValidationException when it is not a long
   */
  public long pathLong(String name) {
    String v = pathParam(name);
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      throw ValidationException.of(name, "INVALID", "must be an integer");
    }
  }

  /**
   * Binds the matched route. Called by the router.
   *
   * @param pattern the route pattern
   * @param params the decoded parameters
   */
  void bind(String pattern, Map<String, String> params) {
    this.routePattern = pattern;
    this.pathParams = Map.copyOf(params);
  }

  /**
   * The pattern of the matched route.
   *
   * @return the pattern, or null before routing or when no route matched
   */
  @Nullable String routePattern() {
    return routePattern;
  }

  // ---- query -------------------------------------------------------------------------------

  /**
   * The first value of a query parameter.
   *
   * @param name the name
   * @return the value, or null when absent
   * @throws ValidationException when the query string is not validly percent-encoded
   */
  public @Nullable String query(String name) {
    List<String> values = parsedQuery().get(name);
    return values == null || values.isEmpty() ? null : values.getFirst();
  }

  /**
   * Every value of a query parameter.
   *
   * @param name the name
   * @return the values, possibly empty
   * @throws ValidationException when the query string is not validly percent-encoded
   */
  public List<String> queries(String name) {
    List<String> values = parsedQuery().get(name);
    return values == null ? List.of() : values;
  }

  /**
   * A query parameter that must be present.
   *
   * @param name the name
   * @return the value
   * @throws ValidationException when absent
   */
  public String queryRequired(String name) {
    String v = query(name);
    if (v == null) {
      throw ValidationException.of(name, "REQUIRED", "is required");
    }
    return v;
  }

  /**
   * A query parameter as an int.
   *
   * @param name the name
   * @param defaultValue returned when absent
   * @return the value
   * @throws ValidationException when present and not an int
   */
  public int queryInt(String name, int defaultValue) {
    String v = query(name);
    if (v == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw ValidationException.of(name, "INVALID", "must be an integer");
    }
  }

  /**
   * A query parameter as a long.
   *
   * @param name the name
   * @param defaultValue returned when absent
   * @return the value
   * @throws ValidationException when present and not a long
   */
  public long queryLong(String name, long defaultValue) {
    String v = query(name);
    if (v == null) {
      return defaultValue;
    }
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      throw ValidationException.of(name, "INVALID", "must be an integer");
    }
  }

  /**
   * A query parameter as a boolean: {@code true} or {@code false} only.
   *
   * @param name the name
   * @param defaultValue returned when absent
   * @return the value
   * @throws ValidationException when present and neither
   */
  public boolean queryBool(String name, boolean defaultValue) {
    String v = query(name);
    if (v == null) {
      return defaultValue;
    }
    return switch (v) {
      case "true" -> true;
      case "false" -> false;
      default -> throw ValidationException.of(name, "INVALID", "must be true or false");
    };
  }

  /**
   * A query parameter as an enum constant, matched by exact name.
   *
   * @param <E> the enum type
   * @param name the name
   * @param type the enum class
   * @param defaultValue returned when absent
   * @return the value
   * @throws ValidationException when present and not a constant of the enum
   */
  public <E extends Enum<E>> E queryEnum(String name, Class<E> type, E defaultValue) {
    String v = query(name);
    if (v == null) {
      return defaultValue;
    }
    for (E constant : type.getEnumConstants()) {
      if (constant.name().equals(v)) {
        return constant;
      }
    }
    List<String> names = new ArrayList<>();
    for (E constant : type.getEnumConstants()) {
      names.add(constant.name());
    }
    throw ValidationException.of(name, "INVALID", "must be one of: " + String.join(", ", names));
  }

  private Map<String, List<String>> parsedQuery() {
    Map<String, List<String>> q = query;
    if (q == null) {
      q = new LinkedHashMap<>();
      String raw = this.raw.rawQuery();
      if (raw != null && !raw.isEmpty()) {
        for (String pair : raw.split("&")) {
          if (pair.isEmpty()) {
            continue;
          }
          int eq = pair.indexOf('=');
          String k = Percent.decode(eq < 0 ? pair : pair.substring(0, eq), true);
          String v = eq < 0 ? "" : Percent.decode(pair.substring(eq + 1), true);
          q.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
      }
      query = q;
    }
    return q;
  }

  // ---- body --------------------------------------------------------------------------------

  /**
   * The declared body length.
   *
   * @return the length from {@code Content-Length}, or -1
   */
  long contentLength() {
    return raw.contentLength();
  }

  /**
   * Whether the declared length is over the limit.
   *
   * @return true when {@code Content-Length} exceeds the body limit
   */
  boolean declaredBodyTooLarge() {
    return raw.contentLength() > raw.maxBodyBytes();
  }

  /**
   * The body stream, limited to the body limit; reading past it throws {@link HttpException} with
   * status 413. May be taken once.
   *
   * @return the stream
   * @throws IllegalStateException when the body was already taken as a stream
   */
  public InputStream body() {
    byte @Nullable [] cached = cachedBody;
    if (cached != null) {
      return new ByteArrayInputStream(cached);
    }
    if (streamTaken) {
      throw new IllegalStateException("the request body can be read only once");
    }
    streamTaken = true;
    return new LimitedStream(raw.body(), raw.maxBodyBytes());
  }

  /**
   * Reads the whole body and caches it; later calls return the same bytes.
   *
   * @return the body bytes; do not modify
   * @throws HttpException 413 when over the limit, 400 when the body cannot be read
   * @throws IllegalStateException when the body was already taken as a stream
   */
  public byte[] bodyBytes() {
    byte @Nullable [] cached = cachedBody;
    if (cached != null) {
      return cached;
    }
    if (streamTaken) {
      throw new IllegalStateException("the request body was already taken as a stream");
    }
    streamTaken = true;
    try (InputStream in = new LimitedStream(raw.body(), raw.maxBodyBytes())) {
      cached = in.readAllBytes();
    } catch (IOException e) {
      throw new HttpException(400, "VALIDATION", "the request body could not be read");
    }
    cachedBody = cached;
    return cached;
  }

  /**
   * Decodes the JSON body into the validator's record and validates it.
   *
   * @param <T> the record type
   * @param validator the validator, which also names the record class
   * @return the valid value
   * @throws HttpException 415 when the content type is not {@code application/json}
   * @throws ValidationException when the JSON cannot be decoded or a rule fails
   */
  public <T> T json(Validator<T> validator) {
    String contentType = header("content-type");
    String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (!mediaType.equals("application/json")) {
      throw new HttpException(415, "UNSUPPORTED_MEDIA_TYPE", "content type must be application/json");
    }
    T value;
    try {
      value = Json.mapper().readValue(bodyBytes(), validator.type());
    } catch (JacksonException e) {
      throw Json.toValidation(e);
    }
    if (value == null) {
      throw ValidationException.of("body", "INVALID_JSON", "a body is required");
    }
    return validator.check(value);
  }

  /**
   * The body as UTF-8 text.
   *
   * @return the text
   */
  public String bodyText() {
    return new String(bodyBytes(), StandardCharsets.UTF_8);
  }

  // ---- context and attributes --------------------------------------------------------------

  /**
   * The context built by the {@link ContextResolver}.
   *
   * @return the context, carrying this request's correlation id
   * @throws IllegalStateException on a route not declared with {@code requiresContext}
   */
  public Context context() {
    Context c = context;
    if (c == null) {
      throw new IllegalStateException("this route was not declared with requiresContext()");
    }
    return c;
  }

  void setContext(Context value) {
    this.context = value;
  }

  /**
   * Reads a value set by middleware.
   *
   * @param <T> the value type
   * @param key the key
   * @return the value, or null
   */
  public <T> @Nullable T attribute(Key<T> key) {
    Object v = attributes.get(key);
    return v == null ? null : key.type.cast(v);
  }

  /**
   * Sets a value for later middleware and the handler.
   *
   * @param <T> the value type
   * @param key the key
   * @param value the value
   */
  public <T> void attribute(Key<T> key, T value) {
    attributes.put(key, value);
  }

  /** Enforces the body limit while reading. */
  private static final class LimitedStream extends InputStream {
    private final InputStream in;
    private final long max;
    private long read;

    LimitedStream(InputStream in, long max) {
      this.in = in;
      this.max = max;
    }

    private void count(long n) {
      read += n;
      if (read > max) {
        throw new HttpException(413, "PAYLOAD_TOO_LARGE", "request body is too large");
      }
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
      int n = in.read(buf, off, len);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    @Override
    public void close() throws IOException {
      in.close();
    }
  }
}
