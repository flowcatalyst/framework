package io.flowcatalyst.framework.objectstore.s3;

import io.flowcatalyst.framework.objectstore.ObjectKeys;
import io.flowcatalyst.framework.objectstore.ObjectStore;
import io.flowcatalyst.framework.objectstore.ObjectStoreException;
import java.io.IOException;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/** Values as S3 objects. Closing the store closes the SDK client. */
final class S3ObjectStore implements ObjectStore, AutoCloseable {

  private final String name;
  private final S3Client client;
  private final String bucket;
  private final String prefix;
  private final int maxBytes;

  private S3ObjectStore(String name, S3Client client, String bucket, String prefix, int maxBytes) {
    this.name = name;
    this.client = client;
    this.bucket = bucket;
    this.prefix = prefix;
    this.maxBytes = maxBytes;
  }

  static S3ObjectStore open(String name, S3ObjectStores.S3Store config) {
    S3ClientBuilder b = S3Client.builder().credentialsProvider(DefaultCredentialsProvider.builder().build());
    if (config.region() != null) {
      b.region(Region.of(config.region()));
    }
    if (config.endpoint() != null) {
      // S3-compatible services: path-style addressing, and checksums only where required (not all accept the trailer).
      b.endpointOverride(config.endpoint())
          .forcePathStyle(true)
          .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
          .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
    }
    S3Client client = b.build();
    try {
      if (config.verifyAtStartup()) {
        client.headBucket(HeadBucketRequest.builder().bucket(config.bucket()).build());
      }
    } catch (RuntimeException e) {
      client.close();
      throw new IllegalStateException(
          "S3 bucket '" + config.bucket() + "' cannot be reached (it must exist, and the role needs s3:ListBucket; or turn off verifyAtStartup): " + e.getMessage(), e);
    }
    String p = config.prefix() == null ? "" : config.prefix() + "/";
    return new S3ObjectStore(name, client, config.bucket(), p, config.maxBytes());
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void put(String key, byte[] content) {
    ObjectKeys.check(key);
    if (content.length > maxBytes) {
      throw new IllegalArgumentException("content is " + content.length + " bytes, over the store's limit of " + maxBytes);
    }
    try {
      client.putObject(PutObjectRequest.builder().bucket(bucket).key(prefix + key).build(), RequestBody.fromBytes(content));
    } catch (SdkException e) {
      throw new ObjectStoreException("object store '" + name + "': cannot write '" + key + "': " + e.getMessage(), e);
    }
  }

  @Override
  public Optional<byte[]> get(String key) {
    ObjectKeys.check(key);
    try (ResponseInputStream<GetObjectResponse> in =
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(prefix + key).build())) {
      Long length = in.response().contentLength();
      if (length != null && length > maxBytes) {
        throw new ObjectStoreException(
            "object store '" + name + "': '" + key + "' is " + length + " bytes, over the limit of " + maxBytes);
      }
      return Optional.of(in.readAllBytes());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (SdkException | IOException e) {
      throw new ObjectStoreException("object store '" + name + "': cannot read '" + key + "': " + e.getMessage(), e);
    }
  }

  @Override
  public void delete(String key) {
    ObjectKeys.check(key);
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(prefix + key).build());
    } catch (SdkException e) {
      throw new ObjectStoreException("object store '" + name + "': cannot delete '" + key + "': " + e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
