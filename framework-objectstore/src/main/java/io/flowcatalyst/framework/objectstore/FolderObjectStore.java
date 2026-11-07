package io.flowcatalyst.framework.objectstore;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * Values as files under one folder, a key's {@code /} being folders. A put writes a temporary file
 * beside the target and moves it into place, so a reader sees the old value or the new one. Keys
 * cannot start a segment with a dot (see {@link ObjectKeys}), so temporary files never collide with
 * a key and a key cannot leave the folder.
 *
 * <p>A value's content type is the hidden file {@code .<name>.content-type} beside it (a key's
 * segment never starts with a dot, so it never collides with a key), written the same way; no such
 * file means {@link StoredObject#DEFAULT_CONTENT_TYPE}. The type is written before the value, so a
 * reader racing a put may see the new type with the old bytes for that instant.
 */
final class FolderObjectStore implements ObjectStore {

  private final String name;
  private final Path root;
  private final int maxBytes;

  FolderObjectStore(String name, Path root, int maxBytes) {
    this.name = name;
    this.root = root;
    this.maxBytes = maxBytes;
  }

  /** Every resolved key lies under the root, so it always has a parent folder. */
  private static Path parentOf(Path target) {
    Path parent = target.getParent();
    if (parent == null) {
      throw new IllegalStateException("a key resolved to a path without a parent: " + target);
    }
    return parent;
  }

  @Override
  public String name() {
    return name;
  }

  private Path resolve(String key) {
    ObjectKeys.check(key);
    Path p = root.resolve(key).normalize();
    if (!p.startsWith(root)) {
      throw new IllegalArgumentException("key escapes the store folder");
    }
    return p;
  }

  /** The hidden file holding a value's content type. */
  private static Path typeFile(Path target) {
    return parentOf(target).resolve("." + target.getFileName() + ".content-type");
  }

  @Override
  public void put(String key, byte[] content) {
    put(key, content, StoredObject.DEFAULT_CONTENT_TYPE);
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    Path target = resolve(key);
    StoredObject.checkContentType(contentType);
    ObjectStoreFactories.checkSize(content.length, maxBytes);
    try {
      Path dir = parentOf(target);
      Files.createDirectories(dir);
      refuseLinks(dir);
      if (contentType.equals(StoredObject.DEFAULT_CONTENT_TYPE)) {
        Files.deleteIfExists(typeFile(target));
      } else {
        replace(dir, typeFile(target), contentType.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      replace(dir, target, content);
    } catch (IOException e) {
      throw new ObjectStoreException("object store '" + name + "': cannot write '" + key + "'", e);
    }
  }

  /** Writes a temporary file beside the target and moves it into place. */
  private void replace(Path dir, Path target, byte[] content) throws IOException {
    Path tmp = null;
    try {
      tmp = dir.resolve(".tmp-" + UUID.randomUUID());
      Files.write(tmp, content);
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw e;
    } finally {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
          // the temporary file is hidden and harmless
        }
      }
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    return read(key).map(StoredObject::content);
  }

  @Override
  public Optional<StoredObject> read(String key) {
    Path target = resolve(key);
    try {
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      refuseLinks(parentOf(target));
      long size = Files.size(target);
      if (size > maxBytes) {
        throw new ObjectStoreException(
            "object store '" + name + "': '" + key + "' is " + size + " bytes, over the limit of " + maxBytes);
      }
      byte[] content = Files.readAllBytes(target);
      String type = StoredObject.DEFAULT_CONTENT_TYPE;
      Path typeFile = typeFile(target);
      if (Files.isRegularFile(typeFile, LinkOption.NOFOLLOW_LINKS)) {
        type = new String(Files.readAllBytes(typeFile), java.nio.charset.StandardCharsets.US_ASCII);
      }
      return Optional.of(new StoredObject(content, type));
    } catch (java.nio.file.NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new ObjectStoreException("object store '" + name + "': cannot read '" + key + "'", e);
    }
  }

  @Override
  public void delete(String key) {
    Path target = resolve(key);
    try {
      if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        refuseLinks(parentOf(target));
        Files.deleteIfExists(target);
        Files.deleteIfExists(typeFile(target));
      }
    } catch (IOException e) {
      throw new ObjectStoreException("object store '" + name + "': cannot delete '" + key + "'", e);
    }
  }

  /** A folder reached through a symbolic link could lead outside the store, so it is refused. */
  private void refuseLinks(Path dir) throws IOException {
    Path real = dir.toRealPath();
    if (!real.startsWith(root)) {
      throw new ObjectStoreException("object store '" + name + "': '" + dir + "' leads outside the store folder");
    }
  }
}
