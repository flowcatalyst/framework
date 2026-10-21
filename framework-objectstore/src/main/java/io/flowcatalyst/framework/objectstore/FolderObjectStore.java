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

  @Override
  public void put(String key, byte[] content) {
    Path target = resolve(key);
    ObjectStoreFactories.checkSize(content.length, maxBytes);
    Path tmp = null;
    try {
      Path dir = parentOf(target);
      Files.createDirectories(dir);
      refuseLinks(dir);
      tmp = dir.resolve(".tmp-" + UUID.randomUUID());
      Files.write(tmp, content);
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new ObjectStoreException("object store '" + name + "': cannot write '" + key + "'", e);
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
      return Optional.of(Files.readAllBytes(target));
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
