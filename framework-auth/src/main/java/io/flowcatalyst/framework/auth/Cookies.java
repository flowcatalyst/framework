package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.Request;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The session and login-binding cookies (contract section 5.3): names, attributes and reading. A
 * name sent twice is treated as absent, so a sibling host cannot shadow ours with a second value.
 */
final class Cookies {

  private final boolean secure;

  Cookies(boolean secure) {
    this.secure = secure;
  }

  boolean secure() {
    return secure;
  }

  String sessionName() {
    return secure ? "__Host-fw_session" : "fw_session";
  }

  String loginName(String state) {
    byte[] digest = SessionHandles.sha256(state.getBytes(StandardCharsets.US_ASCII));
    String h = HexFormat.of().formatHex(digest).substring(0, 16);
    return (secure ? "__Host-fw_login_" : "fw_login_") + h;
  }

  /** A {@code Set-Cookie} value. */
  String set(String name, String value, long maxAgeSeconds) {
    return name + "=" + value + "; Path=/; Max-Age=" + Math.max(0, maxAgeSeconds) + "; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "");
  }

  /** A {@code Set-Cookie} value that removes the cookie. */
  String clear(String name) {
    return set(name, "", 0);
  }

  /** RFC 6265 allows a cookie value in double quotes; some clients send it so. */
  private static String unquote(String value) {
    return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
  }

  /** The value of a cookie sent exactly once, else null. */
  static @Nullable String read(Request request, String name) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    String found = null;
    for (String header : request.headers("Cookie")) {
      for (String pair : header.split(";")) {
        String trimmed = pair.trim();
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String n = trimmed.substring(0, eq).trim();
        if (n.equals(name)) {
          counts.merge(n, 1, Integer::sum);
          found = unquote(trimmed.substring(eq + 1).trim());
        }
      }
    }
    Integer count = counts.get(name);
    if (count == null || count != 1 || found == null || found.isEmpty()) {
      return null;
    }
    return found;
  }
}
