package io.flowcatalyst.framework.http.ratelimit;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.http.Request;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The key resolvers of the rate-limiting contract, section 4. */
public final class KeyResolvers {

  private KeyResolvers() {}

  /**
   * The connection's peer address; IPv6 grouped by /64. Never reads a header.
   *
   * @return the resolver
   */
  public static KeyResolver clientAddress() {
    return request -> normalise(request.remoteAddress());
  }

  /**
   * For an application behind {@code trustedProxies} proxies that each append to {@code
   * X-Forwarded-For}: the entry {@code trustedProxies} from the right, the address the outermost
   * trusted proxy saw. Entries further left are the caller's own and are ignored. Falls back to the
   * peer address when there are fewer entries or the entry is not an IP literal.
   *
   * @param trustedProxies 1 to 10
   * @return the resolver
   */
  public static KeyResolver forwardedFor(int trustedProxies) {
    if (trustedProxies < 1 || trustedProxies > 10) {
      throw new IllegalArgumentException("trustedProxies must be 1 to 10");
    }
    return request -> {
      List<String> entries = new ArrayList<>();
      for (String line : request.headers("X-Forwarded-For")) {
        for (String part : line.split(",")) {
          entries.add(part.trim());
        }
      }
      if (entries.size() >= trustedProxies) {
        String chosen = normalise(entries.get(entries.size() - trustedProxies));
        if (chosen != null) {
          return chosen;
        }
      }
      return normalise(request.remoteAddress());
    };
  }

  /**
   * A header's value, hashed with SHA-256 so it never appears in a store, a log or a metric.
   *
   * @param name the header
   * @return the resolver; null when the header is absent or empty
   */
  public static KeyResolver header(String name) {
    return request -> {
      String value = request.header(name);
      return value == null || value.isEmpty() ? null : "h:" + sha256(value);
    };
  }

  /**
   * The route's client ({@code Context.client()}); route middleware on a context route only.
   *
   * @return the resolver; null without a context
   */
  public static KeyResolver client() {
    return request -> {
      Context ctx = context(request);
      return ctx == null ? null : "c:" + ctx.client().value();
    };
  }

  /**
   * The route's principal; route middleware on a context route only.
   *
   * @return the resolver; null without a context or principal
   */
  public static KeyResolver principal() {
    return request -> {
      Context ctx = context(request);
      return ctx == null || ctx.principal() == null ? null : "p:" + ctx.principal().value();
    };
  }

  private static @Nullable Context context(Request request) {
    try {
      return request.context();
    } catch (IllegalStateException e) {
      return null;
    }
  }

  /** An IP literal in canonical form, IPv6 cut to its /64; null when it is not a literal. */
  static @Nullable String normalise(@Nullable String text) {
    if (text == null || text.isEmpty() || text.length() > 64) {
      return null;
    }
    String literal = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
    InetAddress address;
    try {
      address = InetAddress.ofLiteral(literal);
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (address instanceof Inet6Address) {
      byte[] bytes = address.getAddress();
      return "ip6:" + HexFormat.of().formatHex(bytes, 0, 8) + "::/64";
    }
    return "ip:" + address.getHostAddress();
  }

  static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
