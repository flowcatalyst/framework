package io.flowcatalyst.framework.objectstore.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.objectstore.ObjectStore;
import io.flowcatalyst.framework.objectstore.ObjectStoreContract;
import io.flowcatalyst.framework.objectstore.ObjectStoreException;
import io.flowcatalyst.framework.objectstore.ObjectStores;
import io.flowcatalyst.framework.objectstore.ObjectStoresModule;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The S3 driver against an in-process S3: the real SDK, real HTTP, plain S3 semantics. */
class S3ObjectStoreTest extends ObjectStoreContract {

  private static FakeS3 s3;
  private static App app;
  private static ObjectStore store;

  @TempDir static Path dir;

  @BeforeAll
  static void start() throws Exception {
    System.setProperty("aws.accessKeyId", "test");
    System.setProperty("aws.secretAccessKey", "test");
    s3 = new FakeS3("test-bucket");
    app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of())
            .module(
                ObjectStoresModule.builder()
                    .store("s3", S3ObjectStores.s3("test-bucket").withEndpoint(s3.endpoint()).withRegion("us-east-1").withPrefix("p/q").withMaxBytes(4096))
                    .build())
            .start();
    store = app.get(ObjectStores.class).get("s3");
  }

  @AfterAll
  static void stop() throws Exception {
    app.close();
    s3.close();
  }

  @Override
  protected ObjectStore store() {
    return store;
  }

  @Override
  protected int limit() {
    return 4096;
  }

  @Test
  void valuesLiveUnderThePrefixInTheBucket() {
    store.put("hello/world.json", new byte[] {1, 2});
    assertThat(s3.objects).containsKey("p/q/hello/world.json");
  }

  @Test
  void aServerErrorOnReadWriteAndDeleteIsAnObjectStoreException() {
    s3.failNext.set(100);
    try {
      assertThatThrownBy(() -> store.get("x")).isInstanceOf(ObjectStoreException.class).hasMessageContaining("cannot read 'x'");
      assertThatThrownBy(() -> store.put("x", new byte[] {1})).isInstanceOf(ObjectStoreException.class).hasMessageContaining("cannot write 'x'");
      assertThatThrownBy(() -> store.delete("x")).isInstanceOf(ObjectStoreException.class).hasMessageContaining("cannot delete 'x'");
    } finally {
      s3.failNext.set(0);
    }
  }

  @Test
  void aMissingBucketFailsStartupNamingTheStore() throws Exception {
    Throwable t =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                App.builder()
                    .configDirectory(dir)
                    .environment(Map.of())
                    .module(ObjectStoresModule.builder().store("lost", S3ObjectStores.s3("no-such-bucket").withEndpoint(s3.endpoint()).withRegion("us-east-1")).build())
                    .start()
                    .close());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("object store 'lost'").contains("no-such-bucket");
  }

  @Test
  void verifyAtStartupCanBeTurnedOff() {
    try (App a =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of())
            .module(
                ObjectStoresModule.builder()
                    .store("quiet", S3ObjectStores.s3("no-such-bucket").withEndpoint(s3.endpoint()).withRegion("us-east-1").withVerifyAtStartup(false))
                    .build())
            .start()) {
      assertThat(a.get(ObjectStores.class).get("quiet")).isNotNull();
    }
  }

  @Test
  void badBucketNamesAndPrefixesAreRefusedWhenBuilt() {
    for (String bad : new String[] {"", "UPPER", "a", "has space", "-lead", "trail-"}) {
      assertThatThrownBy(() -> S3ObjectStores.s3(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> S3ObjectStores.s3("good-name").withPrefix("../x")).isInstanceOf(IllegalArgumentException.class);
  }
}
