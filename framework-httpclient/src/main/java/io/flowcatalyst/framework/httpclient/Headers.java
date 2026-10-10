package io.flowcatalyst.framework.httpclient;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Header checks shared by requests, defaults and auth: no injection, no framing headers. */
final class Headers {

  private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");
  private static final Set<String> RESERVED =
      Set.of("host", "content-length", "transfer-encoding", "connection");

  private Headers() {}

  static void checkName(String name) {
    if (!TOKEN.matcher(name).matches()) {
      throw new IllegalArgumentException("not a header name: " + name.replaceAll("[^ -~]", "?"));
    }
    if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("header " + name + " is set by the client");
    }
  }

  static void checkValue(String name, String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\r' || c == '\n' || c == 0) {
        throw new IllegalArgumentException("the value of header " + name + " contains CR, LF or NUL");
      }
    }
  }

  static void check(String name, String value) {
    checkName(name);
    checkValue(name, value);
  }
}
