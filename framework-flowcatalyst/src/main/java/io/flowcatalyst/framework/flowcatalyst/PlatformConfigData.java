package io.flowcatalyst.framework.flowcatalyst;

import org.jspecify.annotations.Nullable;

/**
 * What the platform-configuration calls read and send (platform-config contract section 2): plain
 * values, no SDK types. A secret's value is in {@link Revealed} and in {@link SetRequest}, and in no
 * other record; both print without it.
 */
public final class PlatformConfigData {
  private PlatformConfigData() {}

  /** A secret property. */
  public static final String SECRET = "SECRET";

  /** A plain property. */
  public static final String PLAIN = "PLAIN";

  /**
   * One property of the application as the platform lists or sets it.
   *
   * @param section the section
   * @param property the property
   * @param clientId the platform client id the value is for, or null for the global value
   * @param valueType {@code PLAIN} or {@code SECRET}
   * @param value the value; the literal {@code ***} for a secret
   * @param description the description, or null
   * @param updatedAt when it last changed, as the platform sends it
   */
  public record Property(
      String section,
      String property,
      @Nullable String clientId,
      String valueType,
      String value,
      @Nullable String description,
      String updatedAt) {}

  /**
   * A property to write.
   *
   * @param value the value (the secret's plaintext for a secret)
   * @param valueType {@code PLAIN} or {@code SECRET}
   * @param description the description, or null
   */
  public record SetRequest(String value, String valueType, @Nullable String description) {
    @Override
    public String toString() {
      return "SetRequest[value=withheld, valueType=" + valueType + "]";
    }
  }

  /**
   * A secret's plaintext, as the reveal call answers it.
   *
   * @param section the section
   * @param property the property
   * @param clientId the platform client id the value is for, or null for the global value
   * @param value the plaintext
   * @param updatedAt when it last changed, as the platform sends it
   */
  public record Revealed(String section, String property, @Nullable String clientId, String value, String updatedAt) {
    @Override
    public String toString() {
      return "Revealed[section=" + section + ", property=" + property + ", clientId=" + clientId + ", value=withheld]";
    }
  }
}
