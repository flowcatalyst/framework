package io.flowcatalyst.framework.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real OIDC provider for tests (contract section 9), shaped like the FlowCatalyst platform:
 * discovery, JWKS, {@code /oauth/authorize} (signs the configured user in at once), {@code
 * /oauth/token} (single-use codes, exact {@code redirect_uri}, PKCE S256, public and confidential
 * clients), {@code /auth/oidc/session/end}. It can be told to misbehave.
 */
public final class TestOidcProvider implements AutoCloseable {

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  /** How the next id_tokens are signed. */
  public enum Signing {
    /** RS256 with the published key. */
    NORMAL,
    /** {@code alg: none}, empty signature. */
    NONE,
    /** HS256 with the public key's encoded bytes as the HMAC secret: algorithm confusion. */
    HS256_WITH_PUBLIC_KEY,
    /** RS256 with a key the provider does not publish, under the published kid. */
    OTHER_KEY,
    /** RS256, then one signature byte flipped. */
    TAMPERED_SIGNATURE,
    /** RS256, then the payload replaced by another one (signature of the original). */
    TAMPERED_PAYLOAD
  }

  private record Client(String id, String secret, String redirectUri) {}

  private record Grant(String clientId, String redirectUri, String challenge, String nonce, String scope) {}

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final HttpServer server;
  private final InstantSource clock;
  private final Map<String, Client> clients = new ConcurrentHashMap<>();
  private final Map<String, Grant> codes = new ConcurrentHashMap<>();
  private final AtomicInteger keyFetches = new AtomicInteger();
  private final AtomicInteger discoveryFetches = new AtomicInteger();
  private final AtomicInteger tokenRequests = new AtomicInteger();

  private volatile KeyPair keys = newKeys();
  private volatile String keyId = "key-1";
  private volatile boolean down;
  private volatile Map<String, Object> user = defaultUser();
  private volatile Map<String, Object> idTokenChanges = Map.of();
  private volatile Map<String, Object> idTokenHeaderChanges = Map.of();
  private volatile Map<String, Object> discoveryChanges = Map.of();
  private volatile Signing signing = Signing.NORMAL;
  private volatile String authorizeError = "";
  private volatile int tokenStatus = 200;
  private volatile Map<String, String> lastTokenForm = Map.of();
  private volatile String lastTokenAuthorization = "";

  private TestOidcProvider(InstantSource clock) throws IOException {
    this.clock = clock;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/.well-known/openid-configuration", this::discovery);
    server.createContext("/.well-known/jwks.json", this::jwks);
    server.createContext("/oauth/authorize", this::authorize);
    server.createContext("/oauth/token", this::token);
    server.createContext("/auth/oidc/session/end", e -> json(e, 200, Map.of("message", "Session ended")));
    server.start();
  }

  /**
   * Starts a provider on a free port.
   *
   * @param clock the clock tokens are issued by; share the application's so times line up
   * @return the provider
   */
  public static TestOidcProvider start(InstantSource clock) {
    try {
      return new TestOidcProvider(clock);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The issuer, which is also the base URL.
   *
   * @return {@code http://127.0.0.1:<port>}
   */
  public URI issuer() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  /**
   * Registers a login client.
   *
   * @param clientId the client id
   * @param secret the secret, or empty for a PUBLIC client
   * @param redirectUri the only redirect URI accepted
   */
  public void client(String clientId, String secret, String redirectUri) {
    clients.put(clientId, new Client(clientId, secret, redirectUri));
  }

  /**
   * The claims of the user who signs in at {@code /oauth/authorize} (merged over {@code sub}
   * {@code prn_USER1}, {@code type} {@code USER}, {@code tier} {@code ANCHOR}, {@code clients}
   * {@code ["*"]}).
   *
   * @param claims claims to set
   */
  public void signInAs(Map<String, Object> claims) {
    Map<String, Object> merged = defaultUser();
    merged.putAll(claims);
    user = merged;
  }

  /**
   * Claims of the next id_tokens to set, or with a null value to remove.
   *
   * @param changes the changes
   */
  public void idTokenChanges(Map<String, Object> changes) {
    idTokenChanges = new LinkedHashMap<>(changes);
  }

  /**
   * Header members of the next id_tokens to set, or with a null value to remove.
   *
   * @param changes the changes
   */
  public void idTokenHeaderChanges(Map<String, Object> changes) {
    idTokenHeaderChanges = new LinkedHashMap<>(changes);
  }

  /**
   * How the next id_tokens are signed.
   *
   * @param value the way
   */
  public void idTokenSigning(Signing value) {
    signing = value;
  }

  /**
   * Members of the discovery document to set, or with a null value to remove.
   *
   * @param changes the changes
   */
  public void discoveryChanges(Map<String, Object> changes) {
    discoveryChanges = new LinkedHashMap<>(changes);
  }

  /**
   * Makes {@code /oauth/authorize} redirect with this error; empty for normal sign-in.
   *
   * @param error for example {@code access_denied}
   */
  public void authorizeError(String error) {
    authorizeError = error;
  }

  /**
   * The status {@code /oauth/token} answers with; 200 is normal, 4xx answers {@code invalid_grant}.
   *
   * @param status the status
   */
  public void tokenStatus(int status) {
    tokenStatus = status;
  }

  /** Replaces the signing key with a new one under a new kid. */
  public void rotateKey() {
    keys = newKeys();
    keyId = "key-" + System.nanoTime();
  }

  /**
   * Makes every endpoint answer 503, or work again.
   *
   * @param value true for down
   */
  public void down(boolean value) {
    down = value;
  }

  /** How often the key set was read. @return the count */
  public int keyFetches() {
    return keyFetches.get();
  }

  /** How often discovery was read. @return the count */
  public int discoveryFetches() {
    return discoveryFetches.get();
  }

  /** How often the token endpoint was called. @return the count */
  public int tokenRequests() {
    return tokenRequests.get();
  }

  /** The last token request's form. @return name to value */
  public Map<String, String> lastTokenForm() {
    return lastTokenForm;
  }

  /** The last token request's Authorization header, or empty. @return the header */
  public String lastTokenAuthorization() {
    return lastTokenAuthorization;
  }

  /**
   * A platform access token ({@code token_use=api}, audience the issuer) valid for an hour.
   *
   * @param changes claims to set over the usual ones, or with a null value to remove
   * @return the signed token
   */
  public String accessToken(Map<String, Object> changes) {
    Instant now = clock.instant();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", issuer().toString());
    claims.put("aud", issuer().toString());
    claims.put("sub", "prn_SERVICE1");
    claims.put("type", "SERVICE");
    claims.put("tier", "ANCHOR");
    claims.put("token_use", "api");
    claims.put("scope", "");
    claims.put("clients", List.of("*"));
    claims.put("azp", "svc-client");
    claims.put("iat", now.getEpochSecond());
    claims.put("nbf", now.getEpochSecond());
    claims.put("exp", now.plusSeconds(3600).getEpochSecond());
    apply(claims, changes);
    return sign(header(), claims, Signing.NORMAL);
  }

  /**
   * Signs any claims with the published key under its kid.
   *
   * @param claims the claims
   * @return the compact token
   */
  public String sign(Map<String, Object> claims) {
    return sign(header(), claims, Signing.NORMAL);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  // ---- endpoints ---------------------------------------------------------------------------

  private void discovery(HttpExchange e) throws IOException {
    discoveryFetches.incrementAndGet();
    String base = issuer().toString();
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("issuer", base);
    doc.put("authorization_endpoint", base + "/oauth/authorize");
    doc.put("token_endpoint", base + "/oauth/token");
    doc.put("jwks_uri", base + "/.well-known/jwks.json");
    doc.put("end_session_endpoint", base + "/auth/oidc/session/end");
    doc.put("response_types_supported", List.of("code"));
    doc.put("code_challenge_methods_supported", List.of("S256"));
    doc.put("id_token_signing_alg_values_supported", List.of("RS256"));
    apply(doc, discoveryChanges);
    json(e, 200, doc);
  }

  private void jwks(HttpExchange e) throws IOException {
    keyFetches.incrementAndGet();
    RSAPublicKey key = (RSAPublicKey) keys.getPublic();
    json(
        e,
        200,
        Map.of(
            "keys",
            List.of(
                Map.of(
                    "kty", "RSA", "use", "sig", "alg", "RS256", "kid", keyId,
                    "n", B64.encodeToString(unsigned(key.getModulus())),
                    "e", B64.encodeToString(unsigned(key.getPublicExponent()))))));
  }

  private void authorize(HttpExchange e) throws IOException {
    Map<String, String> q = form(e.getRequestURI().getRawQuery());
    Client client = clients.get(q.getOrDefault("client_id", ""));
    String redirect = q.getOrDefault("redirect_uri", "");
    String state = q.getOrDefault("state", "");
    if (client == null || !client.redirectUri().equals(redirect) || state.isEmpty()) {
      json(e, 400, Map.of("error", "invalid_request"));
      return;
    }
    if (!authorizeError.isEmpty()) {
      redirect(e, redirect + "?error=" + enc(authorizeError) + "&error_description=" + enc("no <script>") + "&state=" + enc(state));
      return;
    }
    if (!"code".equals(q.get("response_type")) || q.getOrDefault("code_challenge", "").isEmpty() || !"S256".equals(q.get("code_challenge_method"))) {
      redirect(e, redirect + "?error=invalid_request&state=" + enc(state));
      return;
    }
    String code = "code-" + System.nanoTime() + "-" + B64.encodeToString(random(12));
    codes.put(code, new Grant(client.id(), redirect, q.get("code_challenge"), q.getOrDefault("nonce", ""), q.getOrDefault("scope", "")));
    redirect(e, redirect + "?code=" + enc(code) + "&state=" + enc(state));
  }

  private void token(HttpExchange e) throws IOException {
    tokenRequests.incrementAndGet();
    Map<String, String> f = form(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    lastTokenForm = Map.copyOf(f);
    String auth = e.getRequestHeaders().getFirst("Authorization");
    lastTokenAuthorization = auth == null ? "" : auth;
    if (tokenStatus >= 500) {
      json(e, tokenStatus, Map.of("error", "server_error"));
      return;
    }
    if (tokenStatus >= 400) {
      json(e, tokenStatus, Map.of("error", "invalid_grant", "error_description", "nope"));
      return;
    }
    String clientId = f.getOrDefault("client_id", "");
    String presentedSecret = f.get("client_secret");
    if (auth != null && auth.startsWith("Basic ")) {
      String pair = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
      int colon = pair.indexOf(':');
      String basicId = URLDecoder.decode(pair.substring(0, colon), StandardCharsets.UTF_8);
      if (!clientId.isEmpty() && !clientId.equals(basicId)) {
        json(e, 400, Map.of("error", "invalid_request"));
        return;
      }
      clientId = basicId;
      presentedSecret = URLDecoder.decode(pair.substring(colon + 1), StandardCharsets.UTF_8);
    }
    Client client = clients.get(clientId);
    if (client == null) {
      json(e, 401, Map.of("error", "invalid_client"));
      return;
    }
    if (client.secret().isEmpty() ? presentedSecret != null : !client.secret().equals(presentedSecret)) {
      json(e, 401, Map.of("error", "invalid_client"));
      return;
    }
    Grant grant = codes.remove(f.getOrDefault("code", ""));
    if (!"authorization_code".equals(f.get("grant_type"))
        || grant == null
        || !grant.clientId().equals(clientId)
        || !grant.redirectUri().equals(f.get("redirect_uri"))
        || !challenge(f.getOrDefault("code_verifier", "")).equals(grant.challenge())) {
      json(e, 400, Map.of("error", "invalid_grant"));
      return;
    }
    Instant now = clock.instant();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", issuer().toString());
    claims.put("aud", clientId);
    claims.put("azp", clientId);
    claims.put("exp", now.plusSeconds(300).getEpochSecond());
    claims.put("iat", now.getEpochSecond());
    claims.put("auth_time", now.getEpochSecond());
    if (!grant.nonce().isEmpty()) {
      claims.put("nonce", grant.nonce());
    }
    claims.putAll(user);
    apply(claims, idTokenChanges);
    Map<String, Object> header = header();
    apply(header, idTokenHeaderChanges);
    String idToken = sign(header, claims, signing);
    Map<String, Object> access = new LinkedHashMap<>(user);
    access.put("iss", issuer().toString());
    access.put("aud", issuer().toString());
    access.put("token_use", "identity");
    access.put("exp", now.plusSeconds(3600).getEpochSecond());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("access_token", sign(header(), access, Signing.NORMAL));
    body.put("token_type", "Bearer");
    body.put("expires_in", 3600);
    body.put("scope", grant.scope());
    body.put("id_token", idToken);
    json(e, 200, body);
  }

  // ---- signing -----------------------------------------------------------------------------

  private Map<String, Object> header() {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("alg", "RS256");
    h.put("typ", "JWT");
    h.put("kid", keyId);
    return h;
  }

  private String sign(Map<String, Object> header, Map<String, Object> claims, Signing how) {
    try {
      switch (how) {
        case NONE -> {
          Map<String, Object> h = new LinkedHashMap<>(header);
          h.put("alg", "none");
          return part(h) + "." + part(claims) + ".";
        }
        case HS256_WITH_PUBLIC_KEY -> {
          Map<String, Object> h = new LinkedHashMap<>(header);
          h.put("alg", "HS256");
          String signed = part(h) + "." + part(claims);
          Mac mac = Mac.getInstance("HmacSHA256");
          mac.init(new SecretKeySpec(keys.getPublic().getEncoded(), "HmacSHA256"));
          return signed + "." + B64.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
        }
        case OTHER_KEY -> {
          return rs256(header, claims, newKeys());
        }
        case TAMPERED_SIGNATURE -> {
          String token = rs256(header, claims, keys);
          String[] p = token.split("\\.");
          byte[] sig = Base64.getUrlDecoder().decode(p[2]);
          sig[sig.length / 2] ^= 1;
          return p[0] + "." + p[1] + "." + B64.encodeToString(sig);
        }
        case TAMPERED_PAYLOAD -> {
          String token = rs256(header, claims, keys);
          String[] p = token.split("\\.");
          Map<String, Object> changed = new LinkedHashMap<>(claims);
          changed.put("roles", List.of("platform:admin"));
          return p[0] + "." + part(changed) + "." + p[2];
        }
        default -> {
          return rs256(header, claims, keys);
        }
      }
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String rs256(Map<String, Object> header, Map<String, Object> claims, KeyPair pair) throws GeneralSecurityException {
    String signed = part(header) + "." + part(claims);
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(pair.getPrivate());
    signature.update(signed.getBytes(StandardCharsets.US_ASCII));
    return signed + "." + B64.encodeToString(signature.sign());
  }

  private String part(Map<String, Object> map) {
    return B64.encodeToString(mapper.writeValueAsBytes(map));
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static Map<String, Object> defaultUser() {
    Map<String, Object> u = new LinkedHashMap<>();
    u.put("sub", "prn_USER1");
    u.put("type", "USER");
    u.put("tier", "ANCHOR");
    u.put("name", "Ada Lovelace");
    u.put("email", "ada@example.test");
    u.put("clients", List.of("*"));
    u.put("applications", List.of("*"));
    u.put("roles", List.of());
    return u;
  }

  private static void apply(Map<String, Object> target, Map<String, Object> changes) {
    changes.forEach((k, v) -> {
      if (v == null) {
        target.remove(k);
      } else {
        target.put(k, v);
      }
    });
  }

  static String challenge(String verifier) {
    try {
      return B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Map<String, String> form(String raw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return out;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.put(k, v);
    }
    return out;
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private void redirect(HttpExchange e, String location) throws IOException {
    try (e) {
      if (down) {
        e.sendResponseHeaders(503, -1);
        return;
      }
      e.getResponseHeaders().add("Location", location);
      e.sendResponseHeaders(302, -1);
    }
  }

  private void json(HttpExchange e, int status, Map<String, ?> body) throws IOException {
    try (e) {
      if (down) {
        e.sendResponseHeaders(503, -1);
        return;
      }
      byte[] bytes = mapper.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(status, bytes.length);
      e.getResponseBody().write(bytes);
    }
  }

  private static byte[] random(int n) {
    byte[] b = new byte[n];
    new java.security.SecureRandom().nextBytes(b);
    return b;
  }

  private static KeyPair newKeys() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    return bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
  }

  /** Unused list helper kept for symmetry with the platform's array claims. */
  static List<String> list(String... values) {
    return new ArrayList<>(List.of(values));
  }
}
