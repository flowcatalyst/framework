package io.flowcatalyst.framework.objectstore.s3;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.objectstore.ObjectStore;
import io.flowcatalyst.framework.objectstore.ObjectStoreContract;
import io.flowcatalyst.framework.objectstore.ObjectStores;
import io.flowcatalyst.framework.objectstore.ObjectStoresModule;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The same contract against a real S3-compatible service. Runs only when {@code FW_S3_ENDPOINT} is set
 * (for example {@code http://localhost:4566} for LocalStack); it makes a bucket of its own and removes it.
 */
@EnabledIfEnvironmentVariable(named = "FW_S3_ENDPOINT", matches = ".+")
class S3LocalStackTest extends ObjectStoreContract {

  private static final String BUCKET = "fw-objectstore-test-" + Long.toHexString(System.nanoTime());
  private static S3Client admin;
  private static App app;
  private static ObjectStore store;

  @TempDir static Path dir;

  @BeforeAll
  static void start() {
    System.setProperty("aws.accessKeyId", "test");
    System.setProperty("aws.secretAccessKey", "test");
    URI endpoint = URI.create(System.getenv("FW_S3_ENDPOINT"));
    admin = S3Client.builder().endpointOverride(endpoint).region(Region.of("us-east-1")).forcePathStyle(true).build();
    admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of())
            .module(ObjectStoresModule.builder().store("s3", S3ObjectStores.s3(BUCKET).withEndpoint(endpoint).withRegion("us-east-1").withPrefix("contract").withMaxBytes(4096)).build())
            .start();
    store = app.get(ObjectStores.class).get("s3");
  }

  @AfterAll
  static void stop() {
    app.close();
    for (S3Object o : admin.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build()).contents()) {
      admin.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(o.key()).build());
    }
    admin.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
    admin.close();
  }

  @Override
  protected ObjectStore store() {
    return store;
  }

  @Override
  protected int limit() {
    return 4096;
  }
}
