package io.flowcatalyst.framework.objectstore;

import java.util.List;

/**
 * Content types and whether an object store accepts them: the Go framework's verdicts
 * ({@code objectstore.CheckContentType}, which is {@code mime.ParseMediaType} after a length and a
 * printable-ASCII check), recorded 2026-11-07 so both frameworks refuse the same values.
 */
final class MediaTypeTable {

  private MediaTypeTable() {}

  /** Accepted, and stored exactly as given. */
  static final List<String> VALID =
      List.of(
          "image/jpeg",
          "image",
          "IMAGE/JPEG",
          "image/jpeg;",
          "text/plain; charset=utf-8",
          "text/plain;charset=utf-8",
          "text/plain ; charset = utf-8",
          "text/plain; charset=\"utf-8\"",
          "multipart/form-data; boundary=\"a b\"",
          "text/plain; x=\"\"",
          "text/plain; a=1; a=1",
          "application/vnd.api+json",
          "text/plain; x=\"a\\\"b\"",
          "text/plain; x=\"a\\b\"",
          "text/plain; x*=utf-8''a",
          "text/plain; x*0=a; x*1=b",
          "text/plain; x=\"a@b\"",
          "text/plain; x=1;y=2",
          "application/" + "x".repeat(StoredObject.MAX_CONTENT_TYPE_LENGTH - "application/".length()));

  /**
   * Accepted, but read back as given only by a store that does not carry the type as a header (S3
   * trims the spaces around a header value).
   */
  static final List<String> VALID_WITH_SPACES_AROUND = List.of(" image/jpeg ", "text/plain; charset=utf-8 ");

  /** Refused. */
  static final List<String> INVALID =
      List.of(
          " ",
          "image/jpeg; ;",
          "image/jpeg;;",
          "text/plain; x=",
          "text/plain; x",
          "text/plain; =y",
          "text/plain; a=1; a=2",
          "text/plain; a=1; A=2",
          "image/",
          "/jpeg",
          "image /jpeg",
          "image/ jpeg",
          "image/jpeg/x",
          "image/jp eg",
          "not a type",
          "image/jpeg; charset",
          "text/plain\r\nX-Injected: 1",
          "téxt/plain",
          "text/plain; x=\"a\u0001b\"",
          "text/plain; x=\"é\"",
          "text/plain; x=\"unterminated",
          "a@b/c",
          "a/b@c",
          "text/plain; x=a@b",
          "text/plain;\tx=1",
          "text/plain; x=1 y",
          "text/plain x=1",
          "application/" + "x".repeat(StoredObject.MAX_CONTENT_TYPE_LENGTH - "application/".length() + 1));
}
