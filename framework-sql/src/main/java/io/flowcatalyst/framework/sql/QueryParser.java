package io.flowcatalyst.framework.sql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the text of a {@code .sql} query file (checked-SQL contract, section 1).
 *
 * <p>A query starts at {@code -- name: <javaName> <kind>}. Comment lines straight after it are its
 * documentation, except {@code -- param:} and {@code -- column:} lines, which are declarations.
 * The first other line starts the SQL, which runs to the next name line. {@code :name} outside
 * string literals, quoted identifiers and comments is a parameter; {@code ::} is a cast.
 */
public final class QueryParser {

  private static final Pattern NAME_LINE = Pattern.compile("^--\\s*name:(.*)$");
  private static final Pattern NAME_BODY = Pattern.compile("^\\s*(\\S+)\\s+:(\\S+)\\s*$");
  private static final Pattern JAVA_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");
  private static final Pattern PARAM_LINE = Pattern.compile("^--\\s*param:(.*)$");
  private static final Pattern COLUMN_LINE = Pattern.compile("^--\\s*column:(.*)$");
  private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern TYPE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*");

  private QueryParser() {}

  /**
   * Parses a file.
   *
   * @param file the file as it should be shown in messages
   * @param content the file's text
   * @return the queries
   * @throws GenerationException naming the file and line when the file is malformed
   */
  public static QueryFile parse(String file, String content) {
    String[] lines = content.split("\r?\n", -1);
    List<Query> queries = new ArrayList<>();
    Set<String> names = new HashSet<>();

    int i = 0;
    // Before the first query: blank lines and comments only.
    while (i < lines.length && !isNameLine(lines[i])) {
      String t = lines[i].strip();
      if (!t.isEmpty() && !t.startsWith("--")) {
        throw new GenerationException(
            file, i + 1, "SQL before the first '-- name: <name> :one|:many|:exec' line");
      }
      i++;
    }
    while (i < lines.length) {
      int start = i;
      int end = i + 1;
      while (end < lines.length && !isNameLine(lines[end])) {
        end++;
      }
      queries.add(parseQuery(file, lines, start, end, names));
      i = end;
    }
    if (queries.isEmpty()) {
      throw new GenerationException(file, 0, "contains no queries (no '-- name:' line)");
    }
    return new QueryFile(file, queries);
  }

  private static boolean isNameLine(String line) {
    return NAME_LINE.matcher(line.strip()).matches();
  }

  private static Query parseQuery(String file, String[] lines, int start, int end, Set<String> names) {
    int nameLine = start + 1;
    Matcher nm = NAME_LINE.matcher(lines[start].strip());
    if (!nm.matches()) {
      throw new IllegalStateException("not a name line");
    }
    Matcher body = NAME_BODY.matcher(nm.group(1));
    if (!body.matches()) {
      throw new GenerationException(
          file, nameLine, "expected '-- name: <javaName> :one|:many|:exec'");
    }
    String name = body.group(1);
    if (!JAVA_NAME.matcher(name).matches() || Names.isReserved(name)) {
      throw new GenerationException(
          file,
          nameLine,
          "query name '" + name + "' must be a lower-camel Java identifier (not a keyword)");
    }
    if (!names.add(name)) {
      throw new GenerationException(file, nameLine, "query name '" + name + "' is used twice in this file");
    }
    QueryKind kind =
        switch (body.group(2)) {
          case "one" -> QueryKind.ONE;
          case "many" -> QueryKind.MANY;
          case "exec" -> QueryKind.EXEC;
          default ->
              throw new GenerationException(
                  file, nameLine, "unknown kind ':" + body.group(2) + "'; use :one, :many or :exec");
        };

    List<String> doc = new ArrayList<>();
    List<ParamDecl> params = new ArrayList<>();
    List<ColumnDecl> columns = new ArrayList<>();
    int i = start + 1;
    while (i < end) {
      String t = lines[i].strip();
      if (t.isEmpty()) {
        i++;
        continue;
      }
      if (!t.startsWith("--")) {
        break;
      }
      Matcher pm = PARAM_LINE.matcher(t);
      Matcher cm = COLUMN_LINE.matcher(t);
      if (pm.matches()) {
        params.add(parseParamDecl(file, i + 1, pm.group(1)));
      } else if (cm.matches()) {
        columns.add(parseColumnDecl(file, i + 1, cm.group(1)));
      } else {
        String text = t.substring(2);
        doc.add(text.startsWith(" ") ? text.substring(1) : text);
      }
      i++;
    }
    if (i >= end) {
      throw new GenerationException(file, nameLine, "query '" + name + "' has no SQL");
    }
    int sqlLine = i + 1;
    List<String> bodyLines = new ArrayList<>(List.of(lines).subList(i, end));
    while (!bodyLines.isEmpty() && bodyLines.get(bodyLines.size() - 1).isBlank()) {
      bodyLines.remove(bodyLines.size() - 1);
    }
    String sql = dedent(bodyLines);

    List<String> parameterNames = new ArrayList<>();
    List<Integer> parameterLines = new ArrayList<>();
    List<Integer> placeholders = new ArrayList<>();
    String executed = scan(file, sqlLine, sql, parameterNames, parameterLines, placeholders);

    checkDeclarations(file, name, params, columns, parameterNames);
    return new Query(
        name,
        kind,
        nameLine,
        doc,
        params,
        columns,
        executed,
        parameterNames,
        placeholders,
        parameterLines,
        sqlLine);
  }

  private static void checkDeclarations(
      String file,
      String queryName,
      List<ParamDecl> params,
      List<ColumnDecl> columns,
      List<String> parameterNames) {
    Set<String> seen = new HashSet<>();
    for (ParamDecl p : params) {
      if (!parameterNames.contains(p.name())) {
        throw new GenerationException(
            file,
            p.line(),
            "query '" + queryName + "' declares parameter '" + p.name() + "' but does not use :" + p.name());
      }
      if (!seen.add(p.name())) {
        throw new GenerationException(file, p.line(), "parameter '" + p.name() + "' is declared twice");
      }
    }
    seen.clear();
    for (ColumnDecl c : columns) {
      if (!seen.add(c.name())) {
        throw new GenerationException(file, c.line(), "column '" + c.name() + "' is declared twice");
      }
    }
  }

  private static ParamDecl parseParamDecl(String file, int line, String text) {
    List<String> tokens = tokens(text);
    if (tokens.isEmpty() || !IDENT.matcher(tokens.get(0)).matches()) {
      throw new GenerationException(
          file, line, "expected '-- param: <name> [?] [enum] [<type>]'");
    }
    boolean nullable = false;
    boolean isEnum = false;
    String type = null;
    for (int k = 1; k < tokens.size(); k++) {
      String tok = tokens.get(k);
      if (tok.equals("?") && !nullable && type == null && !isEnum) {
        nullable = true;
      } else if (tok.equals("enum") && !isEnum && type == null) {
        isEnum = true;
      } else if (type == null && TYPE_NAME.matcher(tok).matches()) {
        type = tok;
      } else {
        throw new GenerationException(
            file, line, "unexpected '" + tok + "' in '-- param:'; expected '<name> [?] [enum] [<type>]'");
      }
    }
    if (isEnum && type == null) {
      throw new GenerationException(file, line, "'enum' must be followed by a type name");
    }
    return new ParamDecl(tokens.get(0), nullable, type, isEnum, line);
  }

  private static ColumnDecl parseColumnDecl(String file, int line, String text) {
    List<String> tokens = tokens(text);
    if (tokens.isEmpty()) {
      throw new GenerationException(
          file, line, "expected '-- column: <name> [!|?] [enum] [<type>]'");
    }
    String first = tokens.get(0);
    Nullness nullness = Nullness.DEFAULT;
    String name = first;
    int k = 1;
    if (first.endsWith("!") || first.endsWith("?")) {
      nullness = first.endsWith("!") ? Nullness.NON_NULL : Nullness.NULLABLE;
      name = first.substring(0, first.length() - 1);
    } else if (k < tokens.size() && (tokens.get(k).equals("!") || tokens.get(k).equals("?"))) {
      nullness = tokens.get(k).equals("!") ? Nullness.NON_NULL : Nullness.NULLABLE;
      k++;
    }
    if (!IDENT.matcher(name).matches()) {
      throw new GenerationException(
          file, line, "expected '-- column: <name> [!|?] [enum] [<type>]'");
    }
    boolean isEnum = false;
    String type = null;
    for (; k < tokens.size(); k++) {
      String tok = tokens.get(k);
      if (tok.equals("enum") && !isEnum && type == null) {
        isEnum = true;
      } else if (type == null && TYPE_NAME.matcher(tok).matches()) {
        type = tok;
      } else {
        throw new GenerationException(
            file, line, "unexpected '" + tok + "' in '-- column:'; expected '<name> [!|?] [enum] [<type>]'");
      }
    }
    if (isEnum && type == null) {
      throw new GenerationException(file, line, "'enum' must be followed by a type name");
    }
    if (nullness == Nullness.DEFAULT && type == null) {
      throw new GenerationException(file, line, "'-- column: " + name + "' overrides nothing");
    }
    return new ColumnDecl(name, nullness, type, isEnum, line);
  }

  private static List<String> tokens(String text) {
    String t = text.strip();
    return t.isEmpty() ? List.of() : List.of(t.split("\\s+"));
  }

  private static String dedent(List<String> lines) {
    int indent = Integer.MAX_VALUE;
    for (String l : lines) {
      if (l.isBlank()) {
        continue;
      }
      int n = 0;
      while (n < l.length() && (l.charAt(n) == ' ' || l.charAt(n) == '\t')) {
        n++;
      }
      indent = Math.min(indent, n);
    }
    StringBuilder out = new StringBuilder();
    for (int k = 0; k < lines.size(); k++) {
      String l = lines.get(k);
      String stripped = l.isBlank() ? "" : l.substring(indent).stripTrailing();
      if (k > 0) {
        out.append('\n');
      }
      out.append(stripped);
    }
    return out.toString();
  }

  /**
   * Marks which characters of SQL text are code: not inside a string literal, a quoted
   * identifier, a dollar-quoted string or a comment. The quotes and comment markers themselves are
   * not code.
   *
   * @param sql the text
   * @return one flag per character
   */
  public static boolean[] codeMask(String sql) {
    int n = sql.length();
    boolean[] code = new boolean[n];
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        while (i < n && sql.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int depth = 1;
        i += 2;
        while (i < n && depth > 0) {
          if (sql.startsWith("/*", i)) {
            depth++;
            i += 2;
          } else if (sql.startsWith("*/", i)) {
            depth--;
            i += 2;
          } else {
            i++;
          }
        }
      } else if (c == '\'') {
        boolean escapes = i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
            && (i < 2 || !isIdentChar(sql.charAt(i - 2)));
        i++;
        while (i < n) {
          char d = sql.charAt(i);
          if (escapes && d == '\\') {
            i += 2;
          } else if (d == '\'') {
            if (i + 1 < n && sql.charAt(i + 1) == '\'') {
              i += 2;
            } else {
              i++;
              break;
            }
          } else {
            i++;
          }
        }
      } else if (c == '"') {
        i++;
        while (i < n) {
          if (sql.charAt(i) == '"') {
            if (i + 1 < n && sql.charAt(i + 1) == '"') {
              i += 2;
            } else {
              i++;
              break;
            }
          } else {
            i++;
          }
        }
      } else if (c == '$' && (i == 0 || !isIdentChar(sql.charAt(i - 1))) && dollarTagEnd(sql, i) > 0) {
        int tagEnd = dollarTagEnd(sql, i);
        String tag = sql.substring(i, tagEnd);
        int close = sql.indexOf(tag, tagEnd);
        i = close < 0 ? n : close + tag.length();
      } else {
        code[i] = true;
        i++;
      }
    }
    return code;
  }

  /** Index just past the {@code $tag$} opener at {@code i}, or {@code -1} when it is not one. */
  private static int dollarTagEnd(String sql, int i) {
    int j = i + 1;
    if (j < sql.length() && (Character.isLetter(sql.charAt(j)) || sql.charAt(j) == '_')) {
      while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
        j++;
      }
    }
    return j < sql.length() && sql.charAt(j) == '$' ? j + 1 : -1;
  }

  private static boolean isIdentChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  /**
   * Whether the code part of the SQL contains a keyword sequence, case-insensitively.
   *
   * @param sql the text
   * @param regex a regular expression over the text with strings and comments blanked
   * @return true when it matches
   */
  public static boolean codeMatches(String sql, Pattern regex) {
    return regex.matcher(blank(sql)).find();
  }

  private static String blank(String sql) {
    boolean[] code = codeMask(sql);
    StringBuilder b = new StringBuilder(sql.length());
    for (int i = 0; i < sql.length(); i++) {
      b.append(code[i] ? sql.charAt(i) : (sql.charAt(i) == '\n' ? '\n' : ' '));
    }
    return b.toString().toUpperCase(Locale.ROOT);
  }

  /** Replaces parameters with {@code ?}; fills the parameter lists. */
  private static String scan(
      String file,
      int firstLine,
      String sql,
      List<String> parameterNames,
      List<Integer> parameterLines,
      List<Integer> placeholders) {
    boolean[] code = codeMask(sql);
    Map<String, Integer> index = new HashMap<>();
    StringBuilder out = new StringBuilder(sql.length());
    int line = firstLine;
    int semicolonLine = 0;
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (!code[i]) {
        out.append(c);
        if (c == '\n') {
          line++;
        }
        i++;
        continue;
      }
      if (semicolonLine > 0 && !Character.isWhitespace(c)) {
        throw new GenerationException(
            file,
            line,
            "more than one statement: a query is one statement (a ';' on line "
                + semicolonLine
                + " is followed by more SQL)");
      }
      if (c == ';') {
        semicolonLine = line;
        i++;
        continue;
      }
      if (c == '?') {
        throw new GenerationException(
            file,
            line,
            "a literal '?' is not allowed: parameters are written :name (for the jsonb '?' operator"
                + " use jsonb_exists())");
      }
      if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
        out.append("::");
        i += 2;
        continue;
      }
      if (c == ':' && i + 1 < n && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')
          && code[i + 1]) {
        int j = i + 1;
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
          j++;
        }
        String pname = sql.substring(i + 1, j);
        Integer k = index.get(pname);
        if (k == null) {
          k = parameterNames.size();
          index.put(pname, k);
          parameterNames.add(pname);
          parameterLines.add(line);
        }
        placeholders.add(k);
        out.append('?');
        i = j;
        continue;
      }
      out.append(c);
      if (c == '\n') {
        line++;
      }
      i++;
    }
    String text = out.toString().stripTrailing();
    if (text.isBlank()) {
      throw new GenerationException(file, firstLine, "the SQL is empty");
    }
    return text;
  }

  /**
   * Finds the declaration for a name.
   *
   * @param decls the declarations
   * @param name the name
   * @return the declaration, or null
   */
  static @Nullable ParamDecl findParam(List<ParamDecl> decls, String name) {
    for (ParamDecl d : decls) {
      if (d.name().equals(name)) {
        return d;
      }
    }
    return null;
  }
}
