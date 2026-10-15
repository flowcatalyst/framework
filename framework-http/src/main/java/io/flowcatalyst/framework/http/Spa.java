package io.flowcatalyst.framework.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One built single-page application (Vite output: {@code index.html}, content-hashed files under
 * {@code assets/}, a few unhashed files), served by the router as its fallback for a path no route
 * matches (static-assets contract). Declared in code, in a module's {@code boot}:
 *
 * <pre>{@code
 * routes.spa(Spa.classpath("fw-spa").build());              // web/dist copied into the jar by Maven
 * routes.spa(Spa.directory(Path.of("web/dist")).build());    // a local build, for development
 * }</pre>
 *
 * <p>{@link Builder#build()} reads every regular file under the root once and keeps them in memory
 * with their content type and ETag; nothing is looked up per request. Symbolic links are not
 * followed and names starting with {@code .} are not indexed. A missing {@code index.html} is a
 * start-up problem outside {@code APP_ENV} {@code local} and {@code test}.
 */
public final class Spa {

  /** The default limit on the number of indexed files. */
  public static final int DEFAULT_MAX_FILES = 10_000;

  /** The default limit on the indexed bytes (64 MiB). */
  public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

  /** A file of the index. */
  @SuppressWarnings("ArrayRecordComponent")
  record File(byte[] bytes, String contentType, String etag) {}

  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~-]+");
  private static final Pattern CLASSPATH_ROOT = Pattern.compile("[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*");

  private static final Map<String, String> CONTENT_TYPES =
      Map.ofEntries(
          Map.entry(".html", "text/html; charset=utf-8"),
          Map.entry(".htm", "text/html; charset=utf-8"),
          Map.entry(".js", "text/javascript; charset=utf-8"),
          Map.entry(".mjs", "text/javascript; charset=utf-8"),
          Map.entry(".cjs", "text/javascript; charset=utf-8"),
          Map.entry(".css", "text/css; charset=utf-8"),
          Map.entry(".json", "application/json"),
          Map.entry(".map", "application/json"),
          Map.entry(".webmanifest", "application/manifest+json"),
          Map.entry(".txt", "text/plain; charset=utf-8"),
          Map.entry(".xml", "application/xml"),
          Map.entry(".wasm", "application/wasm"),
          Map.entry(".svg", "image/svg+xml"),
          Map.entry(".png", "image/png"),
          Map.entry(".jpg", "image/jpeg"),
          Map.entry(".jpeg", "image/jpeg"),
          Map.entry(".gif", "image/gif"),
          Map.entry(".webp", "image/webp"),
          Map.entry(".avif", "image/avif"),
          Map.entry(".bmp", "image/bmp"),
          Map.entry(".ico", "image/x-icon"),
          Map.entry(".woff", "font/woff"),
          Map.entry(".woff2", "font/woff2"),
          Map.entry(".ttf", "font/ttf"),
          Map.entry(".otf", "font/otf"),
          Map.entry(".eot", "application/vnd.ms-fontobject"));

  /** The static-asset extensions of contract section 4.2: exactly the keys of the type table. */
  static boolean isAssetExtension(String segment) {
    return CONTENT_TYPES.containsKey(extension(segment));
  }

  static String contentType(String name) {
    return CONTENT_TYPES.getOrDefault(extension(name), "application/octet-stream");
  }

  private static String extension(String name) {
    int slash = name.lastIndexOf('/');
    int dot = name.lastIndexOf('.');
    return dot <= slash ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
  }

  private final String source;
  private final List<String> prefix;
  private final Set<String> apiPrefixes;
  private final String assetsDirectory;
  private final Csp csp;
  private final Map<String, File> files;
  private final List<String> problems;

  private Spa(Builder b, Map<String, File> files, List<String> problems) {
    this.source = b.source.describe();
    this.prefix = List.copyOf(b.prefix);
    this.apiPrefixes = Collections.unmodifiableSet(new LinkedHashSet<>(b.apiPrefixes));
    this.assetsDirectory = b.assetsDirectory;
    this.csp = b.csp;
    this.files = Collections.unmodifiableMap(files);
    this.problems = List.copyOf(problems);
  }

  /**
   * An SPA in a directory of the application's classpath: Maven copies {@code web/dist} there
   * (a resource with a {@code targetPath}). Read with the thread's context class loader, or the
   * framework's when there is none. The root must resolve to a {@code file:} directory or a
   * directory inside one {@code jar:} file; this reads that one directory, it does not scan.
   *
   * @param root the directory, for example {@code fw-spa}: segments of {@code [A-Za-z0-9._~-]}, no
   *     leading or trailing {@code /}
   * @return the builder
   * @throws IllegalArgumentException when the root is malformed
   */
  public static Builder classpath(String root) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    return classpath(root, loader != null ? loader : Spa.class.getClassLoader());
  }

  /**
   * An SPA in a directory of a given class loader's classpath.
   *
   * @param root the directory, as for {@link #classpath(String)}
   * @param loader the class loader to read it from
   * @return the builder
   * @throws IllegalArgumentException when the root is malformed
   */
  public static Builder classpath(String root, ClassLoader loader) {
    if (!CLASSPATH_ROOT.matcher(root).matches() || hasDotSegment(root)) {
      throw new IllegalArgumentException(
          "a classpath root is segments of [A-Za-z0-9._~-] separated by '/', without '.' or '..': " + root);
    }
    return new Builder(new Classpath(root, loader));
  }

  /**
   * An SPA in a directory on disk, for development ({@code web/dist} after {@code pnpm --dir web
   * build}). A directory that does not exist is "not built".
   *
   * @param directory the directory
   * @return the builder
   */
  public static Builder directory(Path directory) {
    return new Builder(new Directory(directory));
  }

  private static boolean hasDotSegment(String path) {
    for (String s : path.split("/", -1)) {
      if (s.equals(".") || s.equals("..")) {
        return true;
      }
    }
    return false;
  }

  /** Builds an {@link Spa}. */
  public static final class Builder {
    private final Source source;
    private List<String> prefix = List.of();
    private final Set<String> apiPrefixes = new LinkedHashSet<>();
    private String assetsDirectory = "assets";
    private int maxFiles = DEFAULT_MAX_FILES;
    private long maxBytes = DEFAULT_MAX_BYTES;
    private Csp csp = Csp.strict();

    private Builder(Source source) {
      this.source = source;
    }

    /**
     * Where the SPA is mounted; default {@code /}. Outside it the router's normal 404 answers.
     *
     * @param path {@code /} or literal segments such as {@code /app}, no trailing slash
     * @return this builder
     * @throws IllegalArgumentException when the path is malformed
     */
    public Builder prefix(String path) {
      if (path.equals("/")) {
        this.prefix = List.of();
        return this;
      }
      if (!path.startsWith("/")) {
        throw new IllegalArgumentException("an SPA prefix starts with '/': " + path);
      }
      List<String> segments = new ArrayList<>();
      for (String s : path.substring(1).split("/", -1)) {
        segments.add(segment(s, "an SPA prefix segment"));
      }
      this.prefix = List.copyOf(segments);
      return this;
    }

    /**
     * Adds an API prefix: a first segment (after the SPA's prefix) under which a path no route
     * matches gets the JSON 404, never {@code index.html}. The first segment of every route,
     * {@code api} and {@code auth} are API prefixes already; add one only for a prefix that
     * something outside the router serves.
     *
     * @param segment one path segment, for example {@code runtime}
     * @return this builder
     * @throws IllegalArgumentException when the segment is malformed
     */
    public Builder apiPrefix(String segment) {
      apiPrefixes.add(segment(segment, "an API prefix"));
      return this;
    }

    /**
     * The directory of content-hashed files ({@code build.assetsDir} in Vite); default
     * {@code assets}. Its files are served {@code immutable}; a missing file under it is a 404.
     *
     * @param segment one path segment
     * @return this builder
     * @throws IllegalArgumentException when the segment is malformed
     */
    public Builder assetsDirectory(String segment) {
      this.assetsDirectory = segment(segment, "the assets directory");
      return this;
    }

    /**
     * The most files the index may hold; default {@value Spa#DEFAULT_MAX_FILES}. More is a
     * start-up problem.
     *
     * @param count at least 1
     * @return this builder
     * @throws IllegalArgumentException when the count is below 1
     */
    public Builder maxFiles(int count) {
      if (count < 1) {
        throw new IllegalArgumentException("maxFiles must be at least 1");
      }
      this.maxFiles = count;
      return this;
    }

    /**
     * The most bytes the index may hold; default 64 MiB. More is a start-up problem.
     *
     * @param bytes at least 1
     * @return this builder
     * @throws IllegalArgumentException when the limit is below 1
     */
    public Builder maxBytes(long bytes) {
      if (bytes < 1) {
        throw new IllegalArgumentException("maxBytes must be at least 1");
      }
      this.maxBytes = bytes;
      return this;
    }

    /**
     * The {@code Content-Security-Policy} on every answer of the SPA; default {@link Csp#strict}
     * with no script hashes (static-assets contract section 7). An SPA with an inline script (the
     * colour-mode snippet {@code @flowcatalyst/ui} writes into {@code index.html}) passes its hash:
     * {@code Csp.strict("sha256-...")}; {@link Csp#baseline()} is the explicit opt-in for pages
     * that cannot run under the strict policy.
     *
     * @param policy the policy
     * @return this builder
     */
    public Builder contentSecurityPolicy(Csp policy) {
      this.csp = policy;
      return this;
    }

    /**
     * Reads and indexes the files. Problems with them (a root that is not a directory, the limits,
     * a classpath root in an unsupported location, a read failure) are reported when the route
     * table is compiled, with every other start-up problem.
     *
     * @return the SPA
     */
    public Spa build() {
      Collector c = new Collector(maxFiles, maxBytes);
      try {
        source.load(c);
      } catch (LimitExceeded e) {
        c.problems.add(e.getMessage());
      } catch (IOException | RuntimeException e) {
        c.problems.add("cannot read the SPA from " + source.describe() + ": " + e);
      }
      return new Spa(this, c.files, c.problems);
    }

    private static String segment(String s, String what) {
      if (!SEGMENT.matcher(s).matches() || s.startsWith(".")) {
        throw new IllegalArgumentException(what + " must be one segment of [A-Za-z0-9._~-] not starting with '.': '" + s + "'");
      }
      return s;
    }
  }

  // ---- reading -------------------------------------------------------------------------------

  private static final class LimitExceeded extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LimitExceeded(String message) {
      super(message, null, false, false);
    }
  }

  /** Collects files under the limits. */
  private static final class Collector {
    final int maxFiles;
    final long maxBytes;
    final Map<String, File> files = new LinkedHashMap<>();
    final List<String> problems = new ArrayList<>();
    long bytes;

    Collector(int maxFiles, long maxBytes) {
      this.maxFiles = maxFiles;
      this.maxBytes = maxBytes;
    }

    /** True when a relative name may be indexed: no empty segment, none starting with '.'. */
    static boolean indexable(String relative) {
      for (String s : relative.split("/", -1)) {
        if (s.isEmpty() || s.startsWith(".")) {
          return false;
        }
      }
      return true;
    }

    void add(String relative, long declaredSize, InputStream in) throws IOException {
      if (files.size() >= maxFiles) {
        throw new LimitExceeded("the SPA has more than " + maxFiles + " files (Spa.Builder.maxFiles)");
      }
      long room = maxBytes - bytes;
      if (declaredSize > room) {
        throw tooBig();
      }
      int cap = room >= Integer.MAX_VALUE - 8 ? Integer.MAX_VALUE - 8 : (int) room + 1;
      byte[] content = in.readNBytes(cap);
      if (content.length > room) {
        throw tooBig();
      }
      bytes += content.length;
      files.put(relative, new File(content, contentType(relative), etag(content)));
    }

    private LimitExceeded tooBig() {
      return new LimitExceeded("the SPA has more than " + maxBytes + " bytes (Spa.Builder.maxBytes)");
    }
  }

  static String etag(byte[] content) {
    try {
      byte[] sum = MessageDigest.getInstance("SHA-256").digest(content);
      return "\"" + HexFormat.of().formatHex(sum, 0, 16) + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private sealed interface Source permits Classpath, Directory {
    void load(Collector c) throws IOException;

    String describe();
  }

  private record Directory(Path path) implements Source {
    @Override
    public String describe() {
      return "directory " + path;
    }

    @Override
    public void load(Collector c) throws IOException {
      if (!Files.exists(path)) {
        return; // not built
      }
      if (!Files.isDirectory(path)) {
        c.problems.add("the SPA root " + path + " is not a directory");
        return;
      }
      walk(path.toRealPath(), c);
    }
  }

  private static void walk(Path root, Collector c) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            Path name = dir.getFileName();
            if (!dir.equals(root) && name != null && name.toString().startsWith(".")) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            // without FOLLOW_LINKS a link (to a file or a directory) arrives here as a link
            if (attrs.isSymbolicLink() || !attrs.isRegularFile() || Files.isSymbolicLink(file)) {
              return FileVisitResult.CONTINUE;
            }
            List<String> names = new ArrayList<>();
            for (Path p : root.relativize(file)) {
              names.add(p.toString());
            }
            String relative = String.join("/", names);
            if (!Collector.indexable(relative)) {
              return FileVisitResult.CONTINUE;
            }
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
              c.add(relative, attrs.size(), in);
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private record Classpath(String root, ClassLoader loader) implements Source {
    @Override
    public String describe() {
      return "classpath:" + root;
    }

    @Override
    public void load(Collector c) throws IOException {
      URL url = loader.getResource(root + "/index.html");
      boolean viaIndex = url != null;
      if (url == null) {
        url = loader.getResource(root);
      }
      if (url == null) {
        return; // not built
      }
      switch (url.getProtocol()) {
        case "file" -> {
          Path p;
          try {
            p = Path.of(url.toURI());
          } catch (URISyntaxException | IllegalArgumentException e) {
            c.problems.add(describe() + " resolves to " + url + ", which is not a usable file path");
            return;
          }
          Path dir = viaIndex ? p.getParent() : p;
          if (dir == null || !Files.isDirectory(dir)) {
            c.problems.add(describe() + " resolves to " + url + ", which is not a directory");
            return;
          }
          walk(dir.toRealPath(), c);
        }
        case "jar" -> loadJar(url, c);
        default ->
            c.problems.add(
                describe()
                    + " must resolve to a file: directory or a directory inside a jar: file, not "
                    + url.getProtocol()
                    + ": ("
                    + url
                    + ")");
      }
    }

    private void loadJar(URL url, Collector c) throws IOException {
      URLConnection conn = url.openConnection();
      if (!(conn instanceof JarURLConnection jarConn)) {
        c.problems.add(describe() + " resolves to " + url + ", which cannot be opened as a jar");
        return;
      }
      URL jarUrl = jarConn.getJarFileURL();
      if (!jarUrl.getProtocol().equals("file")) {
        c.problems.add(
            describe() + " is inside a jar that is not a file (" + jarUrl + "); nested jars are not supported");
        return;
      }
      Path jarPath;
      try {
        jarPath = Path.of(jarUrl.toURI());
      } catch (URISyntaxException | IllegalArgumentException e) {
        c.problems.add(describe() + " is inside " + jarUrl + ", which is not a usable file path");
        return;
      }
      String prefix = root + "/";
      try (JarFile jar = new JarFile(jarPath.toFile())) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          JarEntry e = entries.nextElement();
          if (e.isDirectory() || !e.getName().startsWith(prefix)) {
            continue;
          }
          String relative = e.getName().substring(prefix.length());
          if (!Collector.indexable(relative)) {
            continue;
          }
          try (InputStream in = jar.getInputStream(e)) {
            c.add(relative, e.getSize(), in);
          }
        }
      }
    }
  }

  // ---- read by the router --------------------------------------------------------------------

  String source() {
    return source;
  }

  List<String> prefix() {
    return prefix;
  }

  String prefixText() {
    return "/" + String.join("/", prefix);
  }

  Set<String> apiPrefixes() {
    return apiPrefixes;
  }

  String assetsDirectory() {
    return assetsDirectory;
  }

  Csp csp() {
    return csp;
  }

  Map<String, File> files() {
    return files;
  }

  @Nullable File index() {
    return files.get("index.html");
  }

  List<String> problems() {
    return problems;
  }
}
