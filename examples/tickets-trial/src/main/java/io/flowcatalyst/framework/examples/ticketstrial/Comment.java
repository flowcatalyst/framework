package io.flowcatalyst.framework.examples.ticketstrial;

import java.time.Instant;

/**
 * One comment on a ticket. Comments are never edited or removed.
 *
 * @param commentId the comment's id
 * @param authorId the principal who wrote it
 * @param body the text
 * @param postedAt when it was posted
 */
public record Comment(String commentId, String authorId, String body, Instant postedAt) {}
