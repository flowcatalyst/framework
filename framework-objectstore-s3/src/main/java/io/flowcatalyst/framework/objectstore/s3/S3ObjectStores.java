package io.flowcatalyst.framework.objectstore.s3;

import io.flowcatalyst.framework.core.Lookup;
import io.flowcatalyst.framework.objectstore.ObjectKeys;
import io.flowcatalyst.framework.objectstore.ObjectStore;
import io.flowcatalyst.framework.objectstore.ObjectStoreFactories;
import io.flowcatalyst.framework.objectstore.ObjectStoreFactory;
import java.net.URI;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The S3 object store driver, chosen in code. Credentials and region come from the AWS SDK's default
 * provider chains (environment, shared profile, container or instance role), so nothing secret is
 * configured here.
 *
 * <pre>{@code
 * ObjectStoresModule.builder()
 *     .store("archive", S3ObjectStores.s3("acme-archive").withPrefix("ingest/sets"))
 *     .build()
 * }</pre>
 */
public final class S3ObjectStores {

  private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");

  private S3ObjectStores() {}

  /**
   * The S3 driver for one bucket.
   *
   * @param bucket the bucket's name; it must already exist
   * @return the store: no key prefix, the region from the SDK's chain, a 64 MiB limit, and a check
   *     at start-up that the bucket can be reached
   * @throws IllegalArgumentException for a name S3 does not allow
   */
  public static S3Store s3(String bucket) {
    return new S3Store(bucket, null, null, null, ObjectStoreFactories.DEFAULT_MAX_BYTES, true);
  }

  /**
   * The S3 driver.
   *
   * @param bucket the bucket
   * @param prefix a key prefix inside the bucket, or null for none
   * @param region the AWS region, or null for the SDK's default chain ({@code AWS_REGION} and so on)
   * @param endpoint an S3-compatible endpoint (LocalStack, MinIO), or null for AWS; path-style
   *     addressing is used with it
   * @param maxBytes the largest value accepted
   * @param verifyAtStartup whether start-up checks the bucket with a {@code HeadBucket} call (needs
   *     the {@code s3:ListBucket} permission; turn it off if the role lacks it)
   */
  public record S3Store(
      String bucket, @Nullable String prefix, @Nullable String region, @Nullable URI endpoint, int maxBytes, boolean verifyAtStartup)
      implements ObjectStoreFactory {

    /** Checks the values. */
    public S3Store {
      if (!BUCKET.matcher(bucket).matches()) {
        throw new IllegalArgumentException("not a valid S3 bucket name: '" + bucket + "'");
      }
      if (prefix != null) {
        ObjectKeys.check(prefix);
      }
      if (region != null && region.isBlank()) {
        throw new IllegalArgumentException("region must not be blank");
      }
      if (maxBytes < 1) {
        throw new IllegalArgumentException("maxBytes must be at least 1, was " + maxBytes);
      }
    }

    /**
     * A copy that stores everything under a prefix, so several stores can share a bucket.
     *
     * @param value a key such as {@code ingest/sets}, see {@link ObjectKeys#check}
     * @return the copy
     */
    public S3Store withPrefix(String value) {
      return new S3Store(bucket, value, region, endpoint, maxBytes, verifyAtStartup);
    }

    /**
     * A copy for a region.
     *
     * @param value for example {@code eu-west-1}
     * @return the copy
     */
    public S3Store withRegion(String value) {
      return new S3Store(bucket, prefix, value, endpoint, maxBytes, verifyAtStartup);
    }

    /**
     * A copy for an S3-compatible service.
     *
     * @param value the endpoint, for example {@code http://localhost:4566}
     * @return the copy
     */
    public S3Store withEndpoint(URI value) {
      return new S3Store(bucket, prefix, region, value, maxBytes, verifyAtStartup);
    }

    /**
     * A copy with another size limit.
     *
     * @param value at least 1
     * @return the copy
     */
    public S3Store withMaxBytes(int value) {
      return new S3Store(bucket, prefix, region, endpoint, value, verifyAtStartup);
    }

    /**
     * A copy that does or does not check the bucket at start-up.
     *
     * @param value true to check
     * @return the copy
     */
    public S3Store withVerifyAtStartup(boolean value) {
      return new S3Store(bucket, prefix, region, endpoint, maxBytes, value);
    }

    @Override
    public ObjectStore create(String name, Lookup lookup) {
      return S3ObjectStore.open(name, this);
    }
  }
}
