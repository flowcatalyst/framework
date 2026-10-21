package io.flowcatalyst.framework.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderObjectStoreTest extends ObjectStoreContract {

  @TempDir Path dir;

  private ObjectStore store;

  private ObjectStore make() throws IOException {
    if (store == null) {
      store = new FolderObjectStore("t", dir.toRealPath(), 4096);
    }
    return store;
  }

  @Override
  protected ObjectStore store() {
    try {
      return make();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  protected int limit() {
    return 4096;
  }

  @Test
  void aPutLeavesNoTemporaryFileBehind() throws IOException {
    store().put("a/b.json", new byte[] {1});
    try (var files = Files.walk(dir)) {
      assertThat(files.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString())).containsExactly("a/b.json");
    }
  }

  @Test
  void aSymbolicLinkOutOfTheFolderIsNotFollowed() throws IOException {
    Path outside = Files.createTempDirectory("outside");
    Files.writeString(outside.resolve("secret"), "nope");
    Files.createSymbolicLink(dir.resolve("link"), outside);
    ObjectStore s = store();
    assertThatThrownBy(() -> s.put("link/secret", new byte[] {1})).isInstanceOf(ObjectStoreException.class);
    assertThatThrownBy(() -> s.get("link/secret")).isInstanceOf(ObjectStoreException.class);
    assertThat(Files.readString(outside.resolve("secret"))).isEqualTo("nope");
  }

  @Test
  void aValueReplacedByAFileTooLargeOnDiskIsRefusedOnRead() throws IOException {
    ObjectStore s = store();
    s.put("big", new byte[1]);
    Files.write(dir.resolve("big"), new byte[5000]);
    assertThatThrownBy(() -> s.get("big")).isInstanceOf(ObjectStoreException.class).hasMessageContaining("over the limit");
  }

  @Test
  void aKeyUsedAsAValueCannotAlsoBeAFolder() {
    ObjectStore s = store();
    s.put("x", new byte[] {1});
    assertThatThrownBy(() -> s.put("x/y", new byte[] {1})).isInstanceOf(ObjectStoreException.class);
  }
}
