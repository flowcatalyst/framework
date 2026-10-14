package io.flowcatalyst.framework.http.openapi;

import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Finds the Java regular-expression constructs that ECMA-262 (the dialect of a JSON Schema {@code
 * pattern}, and of the browser that checks it) lacks or reads differently (contract section 5.4).
 * A pattern using one is a dump problem: use {@code Rules.custom} instead, which documents nothing.
 */
final class EcmaRegex {

  /** Escapes Java has and ECMA does not, or reads differently ({@code \v} is a class in Java). */
  private static final Set<Character> BAD_ESCAPES =
      Set.of('A', 'Z', 'z', 'G', 'h', 'H', 'v', 'V', 'R', 'X', 'Q', 'E', 'e', 'a', 'N');

  private EcmaRegex() {}

  /**
   * The first construct ECMA-262 cannot take.
   *
   * @param regex a Java regular expression
   * @param flags the flags it was compiled with
   * @return what is wrong, or null when the pattern can be documented
   */
  static @Nullable String problem(String regex, int flags) {
    if (flags != 0) {
      return "it is compiled with flags (" + flags + "), which a JSON Schema pattern cannot carry";
    }
    try {
      Pattern.compile(regex);
    } catch (RuntimeException e) {
      return "it is not a valid regular expression";
    }
    boolean inClass = false;
    for (int i = 0; i < regex.length(); i++) {
      char c = regex.charAt(i);
      if (c == '\\') {
        if (i + 1 >= regex.length()) {
          return "it ends with a lone backslash";
        }
        char n = regex.charAt(i + 1);
        if (BAD_ESCAPES.contains(n)) {
          return "it uses \\" + n + ", which ECMA-262 lacks or reads differently";
        }
        if (n == 'p' || n == 'P') {
          // ECMA-262 reads \p{...} only with the u flag, which a browser's validator may not set
          return "it uses \\" + n + ", a Unicode property class ECMA-262 reads only with the u flag";
        }
        if (n == 'x' && i + 2 < regex.length() && regex.charAt(i + 2) == '{') {
          return "it uses \\x{...}, which ECMA-262 reads differently";
        }
        i++;
        continue;
      }
      if (inClass) {
        if (c == '[') {
          return "it nests a character class (a Java union or intersection)";
        }
        if (c == '&' && i + 1 < regex.length() && regex.charAt(i + 1) == '&') {
          return "it uses && inside a character class (a Java intersection)";
        }
        if (c == ']') {
          inClass = false;
        }
        continue;
      }
      if (c == '[') {
        inClass = true;
        // a ']' or '^]' right after '[' is a literal in Java
        if (i + 1 < regex.length() && regex.charAt(i + 1) == '^') {
          i++;
        }
        if (i + 1 < regex.length() && regex.charAt(i + 1) == ']') {
          return "it starts a character class with ']', which ECMA-262 reads as an empty class";
        }
        continue;
      }
      if (c == '(' && i + 1 < regex.length() && regex.charAt(i + 1) == '?') {
        String rest = regex.substring(i + 2);
        if (rest.startsWith(":") || rest.startsWith("=") || rest.startsWith("!") || rest.startsWith("<=")
            || rest.startsWith("<!")) {
          continue;
        }
        if (rest.startsWith("<")) {
          continue; // a named group, which ECMA-262 (2018) has
        }
        if (rest.startsWith(">")) {
          return "it uses an atomic group (?>...)";
        }
        return "it uses inline flags (?...)";
      }
      if ((c == '*' || c == '+' || c == '?' || c == '}') && i + 1 < regex.length() && regex.charAt(i + 1) == '+') {
        return "it uses a possessive quantifier (" + c + "+)";
      }
    }
    return null;
  }
}
