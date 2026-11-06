package io.flowcatalyst.framework.auth;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Turns a field worker into the permissions its {@code Context} carries (contract section 15.5).
 * Separate from the session and bearer {@link PermissionMapper}. The default is {@link #none()}: a
 * route that requires a permission refuses field workers until the application maps them.
 *
 * <pre>{@code
 * FieldPermissions.byDeviceMode(
 *     Set.of("yard:gate:open", "yard:load:release"),   // a worker's own device
 *     Set.of("yard:gate:open"))                         // a shared kiosk gets less
 * }</pre>
 */
@FunctionalInterface
public interface FieldPermissions {

  /**
   * The permissions of a field worker. Called on every authenticated field request.
   *
   * @param worker the verified worker
   * @return the permissions, possibly empty
   */
  Set<String> permissions(FieldPrincipal worker);

  /**
   * Nothing granted. The default.
   *
   * @return the mapping
   */
  static FieldPermissions none() {
    return worker -> Set.of();
  }

  /**
   * The same permissions for every field worker.
   *
   * @param permissions the permissions; copied
   * @return the mapping
   */
  static FieldPermissions all(String... permissions) {
    return all(Set.of(permissions));
  }

  /**
   * The same permissions for every field worker.
   *
   * @param permissions the permissions; copied
   * @return the mapping
   */
  static FieldPermissions all(Collection<String> permissions) {
    Set<String> fixed = Set.copyOf(permissions);
    return worker -> fixed;
  }

  /**
   * One set for a worker's own device and one for a shared device, because shared kiosks usually
   * get less. A worker with no enrolled device ({@link FieldPrincipal.DeviceMode#UNENROLLED}) gets
   * the shared set too, because the device is not known; use {@link #byDeviceMode(Map)} to give it
   * its own.
   *
   * @param personal the permissions on a {@link FieldPrincipal.DeviceMode#PERSONAL} device
   * @param shared the permissions on a {@link FieldPrincipal.DeviceMode#SHARED} device
   * @return the mapping
   */
  static FieldPermissions byDeviceMode(Collection<String> personal, Collection<String> shared) {
    Set<String> own = Set.copyOf(personal);
    Set<String> kiosk = Set.copyOf(shared);
    return worker ->
        switch (worker.deviceMode()) {
          case PERSONAL -> own;
          case SHARED, UNENROLLED -> kiosk;
        };
  }

  /**
   * One set per device mode. A mode with no entry gets no permissions (it fails closed).
   *
   * @param byMode the permissions of each mode: {@code PERSONAL}, {@code SHARED}, {@code UNENROLLED}
   * @return the mapping
   */
  static FieldPermissions byDeviceMode(Map<FieldPrincipal.DeviceMode, ? extends Collection<String>> byMode) {
    Map<FieldPrincipal.DeviceMode, Set<String>> fixed = new java.util.EnumMap<>(FieldPrincipal.DeviceMode.class);
    byMode.forEach((mode, permissions) -> fixed.put(mode, Set.copyOf(permissions)));
    return worker -> fixed.getOrDefault(worker.deviceMode(), Set.of());
  }
}
