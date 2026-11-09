package io.flowcatalyst.framework.push;

import java.util.List;

/**
 * What a stream listens to and who holds it: a stream token's claims.
 *
 * @param topics the granted topics
 * @param client the stream's client; the platform client's value for platform-wide
 * @param principal the principal id, or {@code ""}
 * @param device the field device, or {@code ""}
 * @param session the field login family, or {@code ""}
 */
record Subscription(List<String> topics, String client, String principal, String device, String session) {}
