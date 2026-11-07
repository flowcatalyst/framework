package io.flowcatalyst.framework.objectstore;

import org.jspecify.annotations.Nullable;

/**
 * A value read with its content type ({@link ObjectStore#read}).
 *
 * <p>The array is the caller's own copy; the record compares it by identity, as Java arrays do.
 *
 * @param content the bytes
 * @param contentType the media type given to {@link ObjectStore#put(String, byte[], String)}, or
 *     {@link #DEFAULT_CONTENT_TYPE} for a value stored without one
 */
public record StoredObject(byte[] content, String contentType) {

  /** The content type of a value stored without one. */
  public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

  /** The longest content type accepted, in characters. */
  public static final int MAX_CONTENT_TYPE_LENGTH = 255;

  /** Checks the content type; an empty one is {@link #DEFAULT_CONTENT_TYPE}. */
  public StoredObject {
    contentType = checkContentType(contentType);
  }

  /**
   * Checks a content type and returns the one to store: {@code null} or {@code ""} is {@link
   * #DEFAULT_CONTENT_TYPE}; anything else must be at most {@link #MAX_CONTENT_TYPE_LENGTH} printable
   * ASCII characters (spaces allowed; it becomes a header, so no line breaks) and a media type as Go's
   * {@code mime.ParseMediaType} reads one (a type, optionally {@code /subtype}, then {@code ;
   * name=value} parameters, a value a token or a quoted string, no parameter twice with different
   * values), so this framework and the Go framework accept the same values. It is stored as given.
   *
   * @param contentType the content type, for example {@code image/jpeg} or {@code text/plain;
   *     charset=utf-8}
   * @return the content type to store
   * @throws IllegalArgumentException when it is not allowed
   */
  public static String checkContentType(@Nullable String contentType) {
    if (contentType == null || contentType.isEmpty()) {
      return DEFAULT_CONTENT_TYPE;
    }
    if (contentType.length() > MAX_CONTENT_TYPE_LENGTH) {
      throw new IllegalArgumentException(
          "a content type is at most " + MAX_CONTENT_TYPE_LENGTH + " characters, was " + contentType.length());
    }
    for (int i = 0; i < contentType.length(); i++) {
      char c = contentType.charAt(i);
      if (c < ' ' || c > '~') {
        throw new IllegalArgumentException("a content type is printable ASCII on one line");
      }
    }
    if (!MediaTypes.valid(contentType)) {
      throw new IllegalArgumentException("'" + contentType + "' is not a media type");
    }
    return contentType;
  }
}
