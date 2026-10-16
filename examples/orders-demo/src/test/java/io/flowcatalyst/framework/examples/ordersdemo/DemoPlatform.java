package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.flowcatalyst.FakePlatform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for the platform that answers the reads the demo's admin pages make (event types and
 * roles of {@code orders-demo}, plus a few of other applications that must never show), for the
 * tests and the end-to-end launcher. Nothing here reaches a real platform.
 */
final class DemoPlatform {
  private DemoPlatform() {}

  /**
   * Starts the fake with the demo's catalogue.
   *
   * @return the running fake; close it when done
   */
  static FakePlatform start() {
    FakePlatform platform = FakePlatform.start();
    DemoDirectory.install(platform);
    reads(platform, 200);
    return platform;
  }

  /**
   * Sets how the fake answers the admin pages' reads.
   *
   * @param platform the fake
   * @param status 200 for the demo's catalogue, anything else for an error answer on every read
   */
  static void reads(FakePlatform platform, int status) {
    DemoDirectory directory = DemoDirectory.of(platform);
    if (directory != null) {
      directory.refuse(status == 200 ? 0 : status);
    }
    if (status != 200) {
      for (String path : List.of("/api/event-types", "/api/event-types/et_3", "/api/roles", "/api/roles/orders-demo:clerk")) {
        platform.respondGet(path, status, "{\"error\":\"refused\"}");
      }
      return;
    }
    platform.respondGet(
        "/api/event-types",
        200,
        "{\"items\":["
            + eventType("et_3", "orders-demo:sales:order:placed", "Order placed", "orders-demo", "CURRENT", true) + ","
            + eventType("et_4", "orders-demo:sales:order:cancelled", "Order cancelled", "orders-demo", "CURRENT", false) + ","
            + eventType("et_5", "orders-demo:stock:item:counted", "Item counted", "orders-demo", "ARCHIVED", false) + ","
            + eventType("et_6", "orders-demo:sales:order:paid", "Order paid", "orders-demo", "CURRENT", false) + ","
            + eventType("et_7", "orders-demo:sales:order:shipped", "Order shipped", "orders-demo", "CURRENT", false) + ","
            + eventType("et_8", "orders-demo:sales:order:refunded", "Order refunded", "orders-demo", "CURRENT", false) + ","
            + eventType("et_10", "orders-demo:sales:order:returned", "Order returned", "orders-demo", "CURRENT", false) + ","
            + eventType("et_11", "orders-demo:stock:item:received", "Item received", "orders-demo", "CURRENT", false) + ","
            + eventType("et_12", "orders-demo:stock:item:adjusted", "Item adjusted", "orders-demo", "CURRENT", false) + ","
            + eventType("et_9", "billing:invoice:invoice:issued", "Invoice issued", "billing", "CURRENT", false)
            + "]}");
    platform.respondGet(
        "/api/event-types/et_3",
        200,
        eventType("et_3", "orders-demo:sales:order:placed", "Order placed", "orders-demo", "CURRENT", true));
    platform.respondGet(
        "/api/roles",
        200,
        "{\"roles\":["
            + role("rol_1", "orders-demo:admin", "Administrator", "orders-demo:admin:config:view", "orders-demo:admin:console:view") + ","
            + role("rol_2", "orders-demo:clerk", "Clerk", "orders-demo:order:read", "orders-demo:order:manage") + ","
            + role("rol_9", "billing:admin", "Billing administrator", "billing:invoice:invoice:issue")
            + "],\"total\":3}");
    platform.respondGet(
        "/api/roles/orders-demo:clerk", 200, role("rol_2", "orders-demo:clerk", "Clerk", "orders-demo:order:read", "orders-demo:order:manage"));
  }

  /** Puts the users and invitations back to where they started (the end-to-end suite calls it between specs). */
  static void resetDirectory(FakePlatform platform) {
    DemoDirectory directory = DemoDirectory.of(platform);
    if (directory != null) {
      directory.reset();
    }
  }

  /**
   * The environment that points the demo at the fake as application {@code orders-demo}.
   *
   * @param platform the fake
   * @return the settings to add to the application's environment
   */
  static Map<String, String> env(FakePlatform platform) {
    return Map.of(
        "FLOWCATALYST_ENABLED", "true",
        "FLOWCATALYST_BASE_URL", platform.baseUrl(),
        "FLOWCATALYST_APPLICATION", "orders-demo",
        "FLOWCATALYST_CLIENT_ID", FakePlatform.CLIENT_ID,
        "FLOWCATALYST_CLIENT_SECRET", FakePlatform.CLIENT_SECRET);
  }

  private static String eventType(String id, String code, String name, String application, String status, boolean versions) {
    String[] p = code.split(":");
    return "{\"id\":\"" + id + "\",\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"description\":\"" + name + " (demo).\""
        + ",\"application\":\"" + application + "\",\"subdomain\":\"" + p[1] + "\",\"aggregate\":\"" + p[2]
        + "\",\"eventName\":\"" + p[3] + "\",\"status\":\"" + status + "\",\"specVersions\":"
        + (versions
            ? "[{\"version\":\"1.0\",\"status\":\"CURRENT\",\"schema\":{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}}]"
            : "[]")
        + "}";
  }

  private static String role(String id, String name, String display, String... permissions) {
    List<String> quoted = new ArrayList<>();
    for (String p : permissions) {
      quoted.add("\"" + p + "\"");
    }
    return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"applicationCode\":\"" + name.substring(0, name.indexOf(':'))
        + "\",\"displayName\":\"" + display + "\",\"source\":\"SDK\",\"clientManaged\":false,\"permissions\":[" + String.join(",", quoted) + "]}";
  }
}
