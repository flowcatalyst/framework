package io.flowcatalyst.framework.httpclient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The client-credentials token of one service: fetched once for all concurrent callers (the lock
 * is held while fetching), reused until {@code expires_in} minus a skew, dropped after a 401.
 * Messages never contain the secret, the token or the endpoint's answer.
 */
final class TokenCache {

  private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);
  private static final Duration MAX_SKEW = Duration.ofSeconds(30);

  private final ClientCredentials credentials;
  private final Transport transport;
  private final ServiceOptions service;
  private final LongSupplier nanos;
  private final ReentrantLock lock = new ReentrantLock();
  private @Nullable String token;
  private long expiresAt;

  TokenCache(ClientCredentials credentials, Transport transport, ServiceOptions service, LongSupplier nanos) {
    this.credentials = credentials;
    this.transport = transport;
    this.service = service;
    this.nanos = nanos;
  }

  /** A valid token, fetching one when there is none or it is about to expire. */
  String token() throws InterruptedException {
    lock.lockInterruptibly();
    try {
      String current = token;
      if (current != null && nanos.getAsLong() - expiresAt < 0) {
        return current;
      }
      return fetch();
    } finally {
      lock.unlock();
    }
  }

  /** Drops the token a request was refused with (not a newer one another caller fetched). */
  void invalidate(String used) {
    lock.lock();
    try {
      if (used.equals(token)) {
        token = null;
      }
    } finally {
      lock.unlock();
    }
  }

  private String fetch() throws InterruptedException {
    String host = String.valueOf(credentials.tokenUrl().getHost());
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "client_credentials");
    if (credentials.scope() != null) {
      form.put("scope", credentials.scope());
    }
    if (credentials.audience() != null) {
      form.put("audience", credentials.audience());
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("Content-Type", List.of("application/x-www-form-urlencoded"));
    headers.put("Accept", List.of("application/json"));
    String secret = credentials.clientSecret().get();
    switch (credentials.method()) {
      case POST -> {
        form.put("client_id", credentials.clientId());
        form.put("client_secret", secret);
      }
      case BASIC -> {
        String pair =
            URLEncoder.encode(credentials.clientId(), StandardCharsets.UTF_8)
                + ":"
                + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        headers.put(
            "Authorization",
            new ArrayList<>(
                List.of("Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)))));
      }
    }
    Transport.Incoming answer;
    long started = nanos.getAsLong();
    try {
      answer =
          transport.exchange(
              new Transport.Outgoing(
                  RequestMethod.POST,
                  credentials.tokenUrl(),
                  headers,
                  RequestBuilder.formBody(form).getBytes(StandardCharsets.UTF_8),
                  service.connectTimeout(),
                  service.requestTimeout(),
                  64 * 1024));
    } catch (Transport.Failure f) {
      throw auth("the token request to " + host + " failed (" + f.kind + ")");
    }
    if (answer.status() < 200 || answer.status() >= 300) {
      throw auth("the token endpoint " + host + " answered " + answer.status());
    }
    JsonNode tree;
    try {
      tree = service.mapper().readTree(answer.body());
    } catch (JacksonException e) {
      throw auth("the token endpoint " + host + " sent an answer that is not JSON");
    }
    JsonNode access = tree == null ? null : tree.get("access_token");
    JsonNode type = tree == null ? null : tree.get("token_type");
    JsonNode expires = tree == null ? null : tree.get("expires_in");
    if (access == null || !access.isString() || access.asString().isBlank()) {
      throw auth("the token endpoint " + host + " sent no access_token");
    }
    if (type != null && !(type.isString() && type.asString().equalsIgnoreCase("bearer"))) {
      throw auth("the token endpoint " + host + " sent a token that is not a bearer token");
    }
    Duration lifetime = DEFAULT_LIFETIME;
    if (expires != null) {
      if (!expires.isNumber() || expires.asLong() <= 0) {
        throw auth("the token endpoint " + host + " sent an invalid expires_in");
      }
      lifetime = Duration.ofSeconds(expires.asLong());
    }
    Duration skew = lifetime.dividedBy(10);
    if (skew.compareTo(MAX_SKEW) > 0) {
      skew = MAX_SKEW;
    }
    String value = access.asString();
    Headers.checkValue("Authorization", value);
    token = value;
    expiresAt = started + lifetime.minus(skew).toNanos();
    return value;
  }

  private static HttpClientException auth(String message) {
    return new HttpClientException(HttpClientException.Kind.AUTH, message, null);
  }
}
