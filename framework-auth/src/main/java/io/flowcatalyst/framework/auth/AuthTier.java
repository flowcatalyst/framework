package io.flowcatalyst.framework.auth;

import org.jspecify.annotations.Nullable;

/** The platform's principal tiers, as the session answer documents them (contract section 4.5). */
enum AuthTier {
  ANCHOR,
  PARTNER,
  CLIENT;

  /** The tier of a token's {@code tier} claim, or null when it has none or one the platform does not issue. */
  static @Nullable AuthTier of(@Nullable String tier) {
    if (tier == null) {
      return null;
    }
    for (AuthTier t : values()) {
      if (t.name().equals(tier)) {
        return t;
      }
    }
    return null;
  }
}
