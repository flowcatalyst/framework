package io.flowcatalyst.framework.ui;

import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Routes;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The module's static files, served from the jar: the kit stylesheet ({@code kit.css}, the Tailwind
 * build of {@code ui-kit/theme}), Datastar ({@code datastar.js}, 1.0.4, vendored) and the icon
 * sprite. They are read once; each URL carries a content hash ({@code ?v=}) so it can be cached for
 * a year, and the files answer {@code If-None-Match} with 304.
 */
public final class UiAssets {

  /** The URL prefix the files are served under. */
  public static final String PREFIX = "/fw-ui/";

  private record File(byte[] bytes, String contentType, String etag, String version) {}

  private static final Map<String, String> TYPES =
      Map.of(
          "kit.css", "text/css; charset=utf-8",
          "datastar.js", "text/javascript; charset=utf-8",
          "lucide-sprite.svg", "image/svg+xml");

  private final Map<String, File> files = new LinkedHashMap<>();

  private UiAssets() {
    for (Map.Entry<String, String> e : TYPES.entrySet()) {
      files.put(e.getKey(), load(e.getKey(), e.getValue()));
    }
  }

  /**
   * Reads the module's files from the classpath.
   *
   * @return the assets
   * @throws IllegalStateException when a file is missing from the jar
   */
  public static UiAssets load() {
    return new UiAssets();
  }

  private static File load(String name, String type) {
    try (InputStream in = UiAssets.class.getResourceAsStream("/fw-ui/" + name)) {
      if (in == null) {
        throw new IllegalStateException("framework-ui asset missing from the jar: fw-ui/" + name);
      }
      byte[] bytes = in.readAllBytes();
      String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      return new File(bytes, type, "\"" + hex.substring(0, 32) + "\"", hex.substring(0, 12));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The file names.
   *
   * @return the names
   */
  public List<String> names() {
    return List.copyOf(files.keySet());
  }

  /**
   * The URL of a file, with its content hash.
   *
   * @param name {@code kit.css}, {@code datastar.js} or {@code lucide-sprite.svg}
   * @return for example {@code /fw-ui/kit.css?v=0123456789ab}
   * @throws IllegalArgumentException for an unknown name
   */
  public String url(String name) {
    File f = files.get(name);
    if (f == null) {
      throw new IllegalArgumentException("unknown asset: " + name);
    }
    return PREFIX + name + "?v=" + f.version();
  }

  /**
   * The sprite URL icons reference ({@code <use href="...#i-name">}); the hash is left off, the
   * fragment must follow the path.
   *
   * @return the sprite path
   */
  public String spriteUrl() {
    return PREFIX + "lucide-sprite.svg";
  }

  /**
   * Registers {@code GET /fw-ui/{file}} on the routes.
   *
   * @param routes the module's routes
   */
  public void register(Routes routes) {
    routes.get(PREFIX + "{file}", request -> serve(request, request.pathParam("file")));
    // The sprite also answers at the path the specs and fixtures use, so icons work without a scope.
    routes.get("/icons/lucide-sprite.svg", request -> serve(request, "lucide-sprite.svg"));
  }

  Response serve(Request request, String name) {
    File f = files.get(name);
    if (f == null) {
      return Response.text(404, "not found");
    }
    String cache =
        request.query("v") != null
            ? "public, max-age=31536000, immutable"
            : "public, max-age=3600, must-revalidate";
    if (matches(request.header("If-None-Match"), f.etag())) {
      return Response.bytes(304, f.contentType(), new byte[0]).header("ETag", f.etag()).header("Cache-Control", cache);
    }
    return Response.bytes(200, f.contentType(), f.bytes()).header("ETag", f.etag()).header("Cache-Control", cache);
  }

  private static boolean matches(@Nullable String header, String etag) {
    if (header == null) {
      return false;
    }
    for (String part : header.split(",")) {
      String t = part.strip();
      if (t.equals("*") || t.equals(etag) || t.equals("W/" + etag)) {
        return true;
      }
    }
    return false;
  }
}
