package io.flowcatalyst.framework.auth;

/**
 * What unsafe calls need (component {@code AuthCsrf}, contract sections 4.3 and 7.2): fixed in this
 * version, documented with {@code const}.
 *
 * @param mode always {@code fetch-metadata}
 * @param jsonBodies always true
 */
record AuthCsrf(String mode, boolean jsonBodies) {

  static final AuthCsrf FIXED = new AuthCsrf("fetch-metadata", true);
}
