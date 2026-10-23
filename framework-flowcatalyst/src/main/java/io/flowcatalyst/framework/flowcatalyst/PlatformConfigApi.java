package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.http.Rules.maxLength;
import static io.flowcatalyst.framework.http.Rules.notBlank;
import static io.flowcatalyst.framework.http.Rules.notNull;

import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.QueryParam;
import io.flowcatalyst.framework.http.Validator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The wire types and operations of the platform-config section (platform-config contract section 4):
 * the application's own platform configuration, with secrets write-only except for the reveal.
 */
public final class PlatformConfigApi {
  private PlatformConfigApi() {}

  /** Whether a property is shown as text or kept secret. */
  public enum ValueType {
    /** Stored and shown as text. */
    PLAIN,
    /** Encrypted at rest; every answer but the reveal shows {@code ***}. */
    SECRET
  }

  /** What a secret's {@code value} is in every answer but the reveal. */
  public static final String MASK = "***";

  /**
   * One property.
   *
   * @param section the section
   * @param property the property
   * @param clientId the platform client id the value is for, absent for the global value
   * @param valueType plain or secret
   * @param value the value; {@code ***} for a secret
   * @param description the description, absent when none
   * @param updatedAt when it last changed
   */
  public record ConfigItem(
      String section,
      String property,
      @Nullable String clientId,
      ValueType valueType,
      String value,
      @Nullable String description,
      String updatedAt) {}

  /**
   * The properties of the application.
   *
   * @param items the properties, global and per client
   */
  public record ConfigList(List<ConfigItem> items) {}

  /**
   * A property to set.
   *
   * @param value the value, at most 65 536 characters; the plaintext for a secret
   * @param valueType plain or secret
   * @param description a description, at most 500 characters
   */
  public record SetBody(String value, ValueType valueType, @Nullable String description) {
    static final Validator<SetBody> V =
        Validator.of(SetBody.class)
            .field("value", SetBody::value, notBlank(), maxLength(65_536))
            .field("valueType", SetBody::valueType, notNull())
            .field("description", SetBody::description, maxLength(500));

    @Override
    public String toString() {
      return "SetBody[value=withheld, valueType=" + valueType + "]";
    }
  }

  /**
   * A secret's plaintext. It is in the answer of the reveal operation and nowhere else.
   *
   * @param section the section
   * @param property the property
   * @param clientId the platform client id the value is for, absent for the global value
   * @param value the plaintext
   * @param updatedAt when it last changed
   */
  public record RevealedValue(String section, String property, @Nullable String clientId, String value, String updatedAt) {
    @Override
    public String toString() {
      return "RevealedValue[section=" + section + ", property=" + property + ", clientId=" + clientId + ", value=withheld]";
    }
  }

  static final QueryParam<@Nullable String> SECTION_FILTER = QueryParam.string("section").optional();
  static final QueryParam<@Nullable String> CLIENT = QueryParam.string("clientId").optional();
  static final PathParam<String> SECTION = PathParam.string("section");
  static final PathParam<String> PROPERTY = PathParam.string("property");

  /** The operations for one application. */
  static final class Operations {
    final Operation<NoBody, ConfigList> list;
    final Operation<SetBody, ConfigItem> set;
    final Operation<NoBody, NoBody> delete;
    final Operation<NoBody, RevealedValue> reveal;

    Operations(String view, String manage, String reveal) {
      list =
          Operation.get("/api/admin/platform-config", ConfigList.class)
              .id("listAdminPlatformConfig")
              .summary("The application's platform configuration; secrets show as ***")
              .tag("admin")
              .query(SECTION_FILTER, CLIENT)
              .permission(view)
              .error(400, "INVALID_NAME", "the section is not a valid name")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      set =
          Operation.put("/api/admin/platform-config/{section}/{property}", SetBody.V, ConfigItem.class)
              .id("setAdminPlatformConfig")
              .summary("Add or change a property; a secret's value is never returned")
              .tag("admin")
              .path(SECTION, PROPERTY)
              .query(CLIENT)
              .permission(manage)
              .error(400, "INVALID_NAME", "the section or property is not a valid name")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      delete =
          Operation.delete("/api/admin/platform-config/{section}/{property}", NoBody.class)
              .id("deleteAdminPlatformConfig")
              .summary("Delete a property; deleting one that is not there is not an error")
              .tag("admin")
              .path(SECTION, PROPERTY)
              .query(CLIENT)
              .permission(manage)
              .error(400, "INVALID_NAME", "the section or property is not a valid name")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      this.reveal =
          Operation.post("/api/admin/platform-config/{section}/{property}/reveal", RevealedValue.class)
              .id("revealAdminPlatformConfig")
              .summary("Read a secret's plaintext; the platform records it")
              .tag("admin")
              .path(SECTION, PROPERTY)
              .query(CLIENT)
              .permission(reveal)
              .error(400, "INVALID_NAME", "the section or property is not a valid name")
              .error(400, "NOT_A_SECRET", "the property is plain: read it from the list")
              .error(404, "CONFIG_NOT_FOUND", "there is no such property")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
    }
  }
}
