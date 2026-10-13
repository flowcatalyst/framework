package io.flowcatalyst.framework.auth;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The platform as a relying party sees it (contract sections 5.1 and 5.2): its discovery document,
 * its signing keys, and form posts to its token endpoint. Only the server reads these; nothing a
 * token says is ever fetched.
 */
final class Platform {

  private static final Logger LOG = LoggerFactory.getLogger(Platform.class);

  static final Duration KEYS_LAST = Duration.ofHours(1);
  static final Duration REFETCH_AT_MOST_EVERY = Duration.ofMinutes(1);
  static final Duration FIRST_READ_RETRY_AFTER = Duration.ofSeconds(5);
  private static final int MAX_BODY_BYTES = 1_048_576;
  private static final int MIN_MODULUS_BITS = 2048;

  /** What discovery says, checked. */
  record Discovery(URI authorizationEndpoint, URI tokenEndpoint, URI jwksUri, @Nullable URI endSessionEndpoint) {}

  /** A form post's answer. */
  record Answer(int status, @Nullable JsonNode json) {}

  private final String issuer;
  private final URI issuerUri;
  private final Duration timeout;
  private final InstantSource clock;
  private final HttpClient http;
  private final ReentrantLock fetching = new ReentrantLock();

  private volatile @Nullable Discovery discovery;
  private volatile Map<String, RSAPublicKey> keys = Map.of();
  private volatile @Nullable Instant keysFetchedAt;
  private volatile @Nullable Instant keysAttemptedAt;

  Platform(URI issuer, Duration timeout, InstantSource clock) {
    this.issuer = normalise(issuer);
    this.issuerUri = URI.create(this.issuer);
    this.timeout = timeout;
    this.clock = clock;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  /** The issuer without a trailing slash. */
  static String normalise(URI issuer) {
    String text = issuer.toString();
    while (text.endsWith("/")) {
      text = text.substring(0, text.length() - 1);
    }
    return text;
  }

  String issuer() {
    return issuer;
  }

  /** The checked discovery document; read once, a failed read is not kept. */
  Discovery discovery() throws PlatformUnavailableException {
    Discovery known = discovery;
    if (known != null) {
      return known;
    }
    fetching.lock();
    try {
      known = discovery;
      if (known == null) {
        known = readDiscovery();
        discovery = known;
      }
      return known;
    } finally {
      fetching.unlock();
    }
  }

  private Discovery readDiscovery() throws PlatformUnavailableException {
    JsonNode doc = getJson(URI.create(issuer + "/.well-known/openid-configuration"));
    String declared = doc.path("issuer").asString("");
    if (!issuer.equals(declared)) {
      throw refusedDiscovery("its issuer is '" + declared + "', not the configured '" + issuer + "'");
    }
    URI authorize = endpoint(doc, "authorization_endpoint");
    URI token = endpoint(doc, "token_endpoint");
    URI jwks = endpoint(doc, "jwks_uri");
    if (!sameOrigin(token) || !sameOrigin(jwks)) {
      throw refusedDiscovery("token_endpoint and jwks_uri must be on the issuer's origin");
    }
    JsonNode methods = doc.path("code_challenge_methods_supported");
    if (methods.isArray()) {
      boolean s256 = false;
      for (JsonNode m : methods) {
        s256 |= "S256".equals(m.asString(""));
      }
      if (!s256) {
        throw refusedDiscovery("it does not support PKCE S256");
      }
    }
    String endSession = doc.path("end_session_endpoint").asString("");
    URI end = null;
    if (!endSession.isEmpty()) {
      try {
        end = URI.create(endSession);
      } catch (IllegalArgumentException e) {
        end = null;
      }
    }
    return new Discovery(authorize, token, jwks, end);
  }

  private URI endpoint(JsonNode doc, String name) throws PlatformUnavailableException {
    String value = doc.path(name).asString("");
    if (value.isEmpty()) {
      throw refusedDiscovery("it names no " + name);
    }
    try {
      URI uri = URI.create(value);
      if (uri.getScheme() == null || uri.getHost() == null) {
        throw refusedDiscovery(name + " is not an absolute URL");
      }
      return uri;
    } catch (IllegalArgumentException e) {
      throw refusedDiscovery(name + " is not a URL");
    }
  }

  private boolean sameOrigin(URI uri) {
    return Objects.equals(uri.getScheme(), issuerUri.getScheme())
        && Objects.equals(uri.getHost(), issuerUri.getHost())
        && port(uri) == port(issuerUri);
  }

  private static int port(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static PlatformUnavailableException refusedDiscovery(String why) {
    LOG.warn("the platform's discovery document is refused: {}", why);
    return new PlatformUnavailableException("the platform's discovery document is refused: " + why);
  }

  /**
   * The signing key with this id. Keys are read on first use, again after an hour, and again when
   * a token names an unknown key, at most once a minute; keys already read stay usable when a
   * later read fails.
   *
   * @return the key, or empty when the platform does not publish it
   * @throws PlatformUnavailableException when no keys have ever been read and they cannot be
   */
  Optional<RSAPublicKey> key(String kid) throws PlatformUnavailableException {
    Instant now = clock.instant();
    Instant fetched = keysFetchedAt;
    if (fetched == null) {
      refreshKeys(true);
    } else if (!keys.containsKey(kid) || !now.isBefore(fetched.plus(KEYS_LAST))) {
      Instant attempted = keysAttemptedAt;
      if (attempted == null || !now.isBefore(attempted.plus(REFETCH_AT_MOST_EVERY))) {
        try {
          refreshKeys(false);
        } catch (PlatformUnavailableException e) {
          LOG.warn("the platform's keys could not be read again; keeping the {} already read: {}", keys.size(), e.getMessage());
        }
      }
    }
    return Optional.ofNullable(keys.get(kid));
  }

  private void refreshKeys(boolean firstTime) throws PlatformUnavailableException {
    fetching.lock();
    try {
      Instant now = clock.instant();
      if (firstTime && keysFetchedAt != null) {
        return;
      }
      Instant attempted = keysAttemptedAt;
      if (firstTime && attempted != null && now.isBefore(attempted.plus(FIRST_READ_RETRY_AFTER))) {
        // the platform is down and no key was ever read: do not call it on every request
        throw new PlatformUnavailableException("the platform's keys could not be read moments ago");
      }
      if (!firstTime && attempted != null && now.isBefore(attempted.plus(REFETCH_AT_MOST_EVERY))) {
        return;
      }
      keysAttemptedAt = now;
      Discovery d = discoveryUnlocked();
      JsonNode set = getJson(d.jwksUri());
      Map<String, RSAPublicKey> read = new HashMap<>();
      for (JsonNode jwk : set.path("keys")) {
        RSAPublicKey key = rsaKey(jwk);
        if (key != null) {
          read.put(jwk.path("kid").asString(""), key);
        }
      }
      keys = Map.copyOf(read);
      keysFetchedAt = now;
    } finally {
      fetching.unlock();
    }
  }

  private Discovery discoveryUnlocked() throws PlatformUnavailableException {
    Discovery known = discovery;
    if (known == null) {
      known = readDiscovery();
      discovery = known;
    }
    return known;
  }

  private static @Nullable RSAPublicKey rsaKey(JsonNode jwk) {
    if (!"RSA".equals(jwk.path("kty").asString(""))) {
      return null;
    }
    String use = jwk.path("use").asString("sig");
    String alg = jwk.path("alg").asString("RS256");
    String kid = jwk.path("kid").asString("");
    if (!use.equals("sig") || !alg.equals("RS256") || kid.isEmpty()) {
      return null;
    }
    try {
      BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asString("")));
      BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asString("")));
      if (n.bitLength() < MIN_MODULUS_BITS || e.signum() <= 0) {
        LOG.warn("ignored the platform key {}: modulus under {} bits or bad exponent", kid, MIN_MODULUS_BITS);
        return null;
      }
      return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      LOG.warn("ignored the platform key {}: it cannot be read", kid);
      return null;
    }
  }

  /** Posts a form; any status is returned, a body that is not JSON gives a null tree. */
  Answer postForm(URI uri, Map<String, String> form, @Nullable String authorization) throws PlatformUnavailableException {
    StringJoiner body = new StringJoiner("&");
    form.forEach((k, v) -> body.add(formEncode(k) + "=" + formEncode(v)));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    HttpResponse<InputStream> response = send(request.build());
    byte[] bytes = read(response);
    try {
      JsonNode json = Records.MAPPER.readTree(bytes);
      return new Answer(response.statusCode(), json != null && json.isObject() ? json : null);
    } catch (JacksonException e) {
      return new Answer(response.statusCode(), null);
    }
  }

  private JsonNode getJson(URI uri) throws PlatformUnavailableException {
    HttpResponse<InputStream> response =
        send(HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET().build());
    byte[] bytes = read(response);
    if (response.statusCode() != 200) {
      throw new PlatformUnavailableException("GET " + uri.getPath() + " answered " + response.statusCode());
    }
    try {
      JsonNode json = Records.MAPPER.readTree(bytes);
      if (json == null || !json.isObject()) {
        throw new PlatformUnavailableException("GET " + uri.getPath() + " did not answer with a JSON object");
      }
      return json;
    } catch (JacksonException e) {
      throw new PlatformUnavailableException("GET " + uri.getPath() + " did not answer with JSON");
    }
  }

  private HttpResponse<InputStream> send(HttpRequest request) throws PlatformUnavailableException {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw new PlatformUnavailableException(request.method() + " " + request.uri().getPath() + " failed: " + e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PlatformUnavailableException("interrupted while calling the platform", e);
    }
  }

  private static byte[] read(HttpResponse<InputStream> response) throws PlatformUnavailableException {
    try (InputStream in = response.body()) {
      byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) {
        throw new PlatformUnavailableException("the platform's answer is over " + MAX_BODY_BYTES + " bytes");
      }
      return bytes;
    } catch (IOException e) {
      throw new PlatformUnavailableException("the platform's answer could not be read", e);
    }
  }

  static String formEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
