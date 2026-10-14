package io.flowcatalyst.framework.http;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** A built SPA on disk, as Vite would leave it, for the static-assets tests. */
final class SpaFixture {

  static final String INDEX =
      "<!doctype html><html><head><script type=\"module\" src=\"/assets/index-abc123.js\"></script>"
          + "<link rel=\"stylesheet\" href=\"/assets/index-abc123.css\"></head><body><div id=\"app\"></div></body></html>";

  static final String SECRET = "TOP-SECRET-OUTSIDE-THE-ROOT";

  private SpaFixture() {}

  /** The files of the fixture build, relative path to content. Dot files are not indexed. */
  static Map<String, byte[]> files() {
    Map<String, byte[]> f = new LinkedHashMap<>();
    f.put("index.html", INDEX.getBytes(StandardCharsets.UTF_8));
    f.put("favicon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8));
    f.put("robots.txt", "User-agent: *\n".getBytes(StandardCharsets.UTF_8));
    f.put("assets/index-abc123.js", "console.log(\"app\")".getBytes(StandardCharsets.UTF_8));
    f.put("assets/index-abc123.css", "body{}".getBytes(StandardCharsets.UTF_8));
    f.put("assets/inter-latin.woff2", new byte[] {0x77, 0x4f, 0x46, 0x32, 0, 1, 2, 3});
    f.put("assets/nested/chunk-def456.mjs", "export {}".getBytes(StandardCharsets.UTF_8));
    f.put("manifest.webmanifest", "{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8));
    f.put("noextension", "plain".getBytes(StandardCharsets.UTF_8));
    f.put("data.bin", new byte[] {1, 2, 3});
    f.put(".vite/manifest.json", "{\"vite\":true}".getBytes(StandardCharsets.UTF_8));
    f.put(".hidden", "hidden".getBytes(StandardCharsets.UTF_8));
    f.put("assets/.editor.js", "editor".getBytes(StandardCharsets.UTF_8));
    return f;
  }

  /**
   * Writes the fixture under {@code parent/dist}, a {@code secret.txt} next to it (outside the
   * root) and, where the file system allows, symbolic links inside the root pointing out of it.
   *
   * @return the root ({@code parent/dist})
   */
  static Path write(Path parent) {
    try {
      Path root = parent.resolve("dist");
      for (Map.Entry<String, byte[]> e : files().entrySet()) {
        Path file = root.resolve(e.getKey());
        Files.createDirectories(file.getParent());
        Files.write(file, e.getValue());
      }
      Files.writeString(parent.resolve("secret.txt"), SECRET);
      Files.createDirectories(parent.resolve("outside"));
      Files.writeString(parent.resolve("outside/x.js"), SECRET);
      try {
        Files.createSymbolicLink(root.resolve("link.txt"), parent.resolve("secret.txt"));
        Files.createSymbolicLink(root.resolve("linkdir"), parent.resolve("outside"));
      } catch (UnsupportedOperationException | IOException e) {
        // a file system without links: the link tests have nothing to check
      }
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes the fixture into a jar under {@code prefix/}, with directory entries, and returns it. */
  static Path jar(Path dir, String prefix, boolean directoryEntries) {
    Path jar = dir.resolve("app.jar");
    try (OutputStream os = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(os)) {
      out.putNextEntry(new JarEntry("other/readme.txt"));
      out.write(SECRET.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
      java.util.Set<String> dirs = new java.util.TreeSet<>();
      for (String name : files().keySet()) {
        String path = prefix + "/" + name;
        for (int i = path.indexOf('/'); i >= 0; i = path.indexOf('/', i + 1)) {
          dirs.add(path.substring(0, i + 1));
        }
      }
      if (directoryEntries) {
        for (String d : dirs) {
          out.putNextEntry(new JarEntry(d));
          out.closeEntry();
        }
      }
      for (Map.Entry<String, byte[]> e : files().entrySet()) {
        out.putNextEntry(new JarEntry(prefix + "/" + e.getKey()));
        out.write(e.getValue());
        out.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return jar;
  }

  /** The contract's ETag, computed here independently of the code under test. */
  static String etag(byte[] bytes) {
    try {
      byte[] sum = MessageDigest.getInstance("SHA-256").digest(bytes);
      return "\"" + HexFormat.of().formatHex(sum, 0, 16) + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String etag(String path) {
    return etag(files().get(path));
  }
}
