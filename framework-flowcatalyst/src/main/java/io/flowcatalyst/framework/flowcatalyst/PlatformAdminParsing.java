package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.AttemptInfo;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.ContextEntry;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.EventDetail;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.EventInfo;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.InvitationInfo;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.InvitationPage;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.InviteResult;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.JobInfo;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.ResendResult;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.UserInfo;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.UserPage;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the platform's JSON into {@link PlatformAdminData}, field by field by name: a field the
 * platform adds (or should never send, such as a token) is not copied.
 */
final class PlatformAdminParsing {
  private PlatformAdminParsing() {}

  private static final JsonMapper PRETTY = JsonMapper.builder().build();

  static UserInfo user(JsonNode n) {
    return new UserInfo(
        text(n, "id"),
        text(n, "email"),
        text(n, "name"),
        bool(n, "active"),
        text(n, "clientId"),
        text(n, "idpType"),
        strings(n.get("roles")),
        opt(n, "invitation"),
        opt(n, "lastLoginAt"),
        text(n, "createdAt"));
  }

  static UserPage users(JsonNode n) {
    List<UserInfo> out = new ArrayList<>();
    for (JsonNode u : array(n.get("users"))) {
      out.add(user(u));
    }
    return new UserPage(List.copyOf(out), integer(n, "total"), integer(n, "page"), integer(n, "pageSize"));
  }

  static InvitationInfo invitation(JsonNode n) {
    return new InvitationInfo(
        text(n, "userId"),
        text(n, "email"),
        text(n, "name"),
        text(n, "clientId"),
        strings(n.get("roles")),
        text(n, "status"),
        text(n, "createdAt"),
        opt(n, "expiresAt"));
  }

  static InvitationPage invitations(JsonNode n) {
    List<InvitationInfo> out = new ArrayList<>();
    for (JsonNode i : array(n.get("invitations"))) {
      out.add(invitation(i));
    }
    return new InvitationPage(List.copyOf(out), integer(n, "total"), integer(n, "page"), integer(n, "pageSize"));
  }

  static InviteResult invite(JsonNode n) {
    return new InviteResult(text(n, "id"), opt(n, "inviteLink"), bool(n, "invitationSent"));
  }

  static ResendResult resend(JsonNode n) {
    return new ResendResult(opt(n, "inviteLink"), opt(n, "expiresAt"));
  }

  static PlatformConfigData.Property configProperty(JsonNode n) {
    return new PlatformConfigData.Property(
        text(n, "section"),
        text(n, "property"),
        opt(n, "clientId"),
        text(n, "valueType"),
        text(n, "value"),
        opt(n, "description"),
        text(n, "updatedAt"));
  }

  static List<PlatformConfigData.Property> configProperties(JsonNode n) {
    List<PlatformConfigData.Property> out = new ArrayList<>();
    for (JsonNode p : array(n.get("items"))) {
      out.add(configProperty(p));
    }
    return List.copyOf(out);
  }

  static PlatformConfigData.Revealed revealed(JsonNode n) {
    return new PlatformConfigData.Revealed(text(n, "section"), text(n, "property"), opt(n, "clientId"), text(n, "value"), text(n, "updatedAt"));
  }

  static EventInfo event(JsonNode n) {
    return new EventInfo(
        text(n, "id"),
        text(n, "type"),
        text(n, "source"),
        opt(n, "subject"),
        text(n, "time"),
        opt(n, "application"),
        opt(n, "messageGroup"),
        opt(n, "correlationId"),
        opt(n, "clientId"));
  }

  static List<EventInfo> events(JsonNode n) {
    List<EventInfo> out = new ArrayList<>();
    for (JsonNode e : array(n)) {
      out.add(event(e));
    }
    return List.copyOf(out);
  }

  static EventDetail eventDetail(JsonNode n) {
    List<ContextEntry> context = new ArrayList<>();
    for (JsonNode c : array(n.get("contextData"))) {
      context.add(new ContextEntry(text(c, "key"), text(c, "value")));
    }
    JsonNode data = n.get("data");
    String dataText = null;
    if (data != null && !data.isNull() && !data.isMissingNode()) {
      dataText = data.isString() ? data.asString() : PRETTY.writerWithDefaultPrettyPrinter().writeValueAsString(data);
    }
    return new EventDetail(event(n), dataText, List.copyOf(context), opt(n, "causationId"));
  }

  static JobInfo job(JsonNode n) {
    return new JobInfo(
        text(n, "id"),
        opt(n, "eventId"),
        opt(n, "subscriptionId"),
        opt(n, "clientId"),
        text(n, "code"),
        text(n, "status"),
        text(n, "kind"),
        text(n, "targetUrl"),
        text(n, "mode"),
        opt(n, "messageGroup"),
        opt(n, "descriptor"),
        text(n, "createdAt"),
        opt(n, "lastAttemptAt"),
        opt(n, "completedAt"),
        integer(n, "attemptCount"),
        opt(n, "protocol"),
        optInt(n, "timeoutSeconds"),
        optInt(n, "maxRetries"),
        opt(n, "retryStrategy"),
        opt(n, "lastError"));
  }

  static List<JobInfo> jobs(JsonNode n) {
    List<JobInfo> out = new ArrayList<>();
    for (JsonNode j : array(n)) {
      out.add(job(j));
    }
    return List.copyOf(out);
  }

  static List<AttemptInfo> attempts(JsonNode n) {
    List<AttemptInfo> out = new ArrayList<>();
    for (JsonNode a : array(n)) {
      JsonNode duration = a.get("durationMillis");
      out.add(
          new AttemptInfo(
              integer(a, "attemptNumber"),
              text(a, "attemptedAt"),
              opt(a, "completedAt"),
              duration != null && duration.isNumber() ? duration.asLong() : null,
              optInt(a, "responseCode"),
              opt(a, "responseBody"),
              bool(a, "success"),
              opt(a, "errorMessage"),
              opt(a, "errorType")));
    }
    return List.copyOf(out);
  }

  // ---- small readers -------------------------------------------------------------------------

  private static Iterable<JsonNode> array(@Nullable JsonNode n) {
    return n != null && n.isArray() ? n : List.<JsonNode>of();
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? "" : v.asString();
  }

  private static @Nullable String opt(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() || !v.isString() ? null : v.asString();
  }

  private static boolean bool(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v != null && v.isBoolean() && v.asBoolean();
  }

  private static int integer(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v != null && v.isNumber() ? v.asInt() : 0;
  }

  private static @Nullable Integer optInt(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v != null && v.isNumber() ? v.asInt() : null;
  }

  private static List<String> strings(@Nullable JsonNode n) {
    List<String> out = new ArrayList<>();
    for (JsonNode v : array(n)) {
      if (v.isString()) {
        out.add(v.asString());
      }
    }
    return List.copyOf(out);
  }
}
