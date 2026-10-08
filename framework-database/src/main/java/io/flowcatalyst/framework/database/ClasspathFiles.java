package io.flowcatalyst.framework.database;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Lists the files of a classpath directory, from directories and from jars. */
final class ClasspathFiles {
  private ClasspathFiles() {}

  /** A file name and its bytes. */
  record Entry(String name, byte[] bytes) {}

  /**
   * Lists regular files directly inside the directory, across every classpath root that has it.
   *
   * @return entries sorted by name; empty when no root has the directory
   */
  static List<Entry> list(ClassLoader loader, String location) throws IOException {
    List<Entry> out = new ArrayList<>();
    Enumeration<URL> roots = loader.getResources(location);
    for (URL url : Collections.list(roots)) {
      URI uri;
      try {
        uri = url.toURI();
      } catch (java.net.URISyntaxException e) {
        throw new IOException("bad classpath URL " + url, e);
      }
      if (uri.getScheme().equals("jar")) {
        FileSystem fs = null;
        boolean created = false;
        try {
          try {
            fs = FileSystems.newFileSystem(uri, Map.of());
            created = true;
          } catch (FileSystemAlreadyExistsException e) {
            fs = FileSystems.getFileSystem(uri);
          }
          readDirectory(fs.provider().getPath(uri), out);
        } finally {
          if (created && fs != null) {
            fs.close();
          }
        }
      } else {
        readDirectory(Path.of(uri), out);
      }
    }
    out.sort((a, b) -> a.name().compareTo(b.name()));
    return out;
  }

  private static void readDirectory(Path dir, List<Entry> out) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      for (Path p : (Iterable<Path>) files::iterator) {
        if (Files.isRegularFile(p)) {
          out.add(new Entry(p.getFileName().toString(), Files.readAllBytes(p)));
        }
      }
    }
  }
}
