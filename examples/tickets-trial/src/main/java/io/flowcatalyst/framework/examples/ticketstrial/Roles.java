package io.flowcatalyst.framework.examples.ticketstrial;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The roles of the application, written once. The definition sync sends them to the platform
 * (where a role is named {@code tickets-trial:<name>}) and the sign-in maps the roles a person
 * holds to the permissions here, so the two cannot drift apart.
 */
public final class Roles {

  /**
   * A role and what it grants.
   *
   * @param name the name without the application prefix
   * @param description what the role is for
   * @param permissions what it grants
   */
  public record RoleDefinition(String name, String description, Set<String> permissions) {

    /**
     * The name on the platform, which is also what the person's sign-in carries.
     *
     * @return {@code application:name}
     */
    public String platformName() {
      return Permissions.APPLICATION + ":" + name;
    }
  }

  /** Opens tickets and comments on their own. */
  public static final RoleDefinition REQUESTER =
      new RoleDefinition(
          "requester",
          "Opens tickets and follows them.",
          Set.of(Permissions.READ, Permissions.OPEN, Permissions.COMMENT));

  /** Works the queue. */
  public static final RoleDefinition AGENT =
      new RoleDefinition(
          "agent",
          "Works tickets: assigns them and moves them along.",
          Set.of(Permissions.READ, Permissions.OPEN, Permissions.WORK, Permissions.COMMENT));

  /** An agent who also sets the client's SLA. */
  public static final RoleDefinition LEAD =
      new RoleDefinition(
          "lead",
          "An agent who also sets the client's SLA policy.",
          Set.of(Permissions.READ, Permissions.OPEN, Permissions.WORK, Permissions.COMMENT, Permissions.SLA_MANAGE));

  /** Every role. The administrator's role is the framework's own ({@code admin}). */
  public static final List<RoleDefinition> ALL = List.of(REQUESTER, AGENT, LEAD);

  private Roles() {}

  /**
   * The permissions each platform role name grants, for the sign-in's permission mapping, with the
   * administrator's wildcard for the admin area (admin contract, section 3).
   *
   * @return role name to permissions
   */
  public static Map<String, Set<String>> byPlatformName() {
    Map<String, Set<String>> out = ALL.stream().collect(Collectors.toMap(RoleDefinition::platformName, RoleDefinition::permissions));
    out.put(Permissions.APPLICATION + ":admin", Set.of(Permissions.APPLICATION + ":admin:*:*"));
    return Map.copyOf(out);
  }
}
