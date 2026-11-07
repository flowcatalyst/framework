package io.flowcatalyst.framework.objectstore;

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

  /** The longest content type accepted. */
  public static final int MAX_CONTENT_TYPE_LENGTH = 255;

  /** Checks the content type. */
  public StoredObject {
    checkContentType(contentType);
  }

  /**
   * Checks a content type: not blank, at most {@link #MAX_CONTENT_TYPE_LENGTH} characters, printable
   * ASCII and spaces only (it becomes a header, so no line breaks).
   *
   * @param contentType the content type, for example {@code image/jpeg}
   * @return the content type
   * @throws IllegalArgumentException when it is not allowed
   */
  public static String checkContentType(String contentType) {
    if (contentType.isBlank() || contentType.length() > MAX_CONTENT_TYPE_LENGTH) {
      throw new IllegalArgumentException(
          "a content type is not blank and at most " + MAX_CONTENT_TYPE_LENGTH + " characters");
    }
    for (int i = 0; i < contentType.length(); i++) {
      char c = contentType.charAt(i);
      if (c < ' ' || c > '~') {
        throw new IllegalArgumentException("a content type is printable ASCII on one line");
      }
    }
    return contentType;
  }
}
