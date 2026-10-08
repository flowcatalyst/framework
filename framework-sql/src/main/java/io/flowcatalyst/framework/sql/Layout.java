package io.flowcatalyst.framework.sql;

import java.nio.file.Path;

/**
 * Where a project keeps its query files and generated sources.
 *
 * @param sqlDir the directory holding the {@code .sql} files; the path below it is the package
 * @param sqlLabel how {@code sqlDir} is shown in generated headers, for example {@code
 *     src/main/sql} (relative to the project, so the output does not depend on the machine)
 * @param outDir the directory generated sources are written to
 * @param outLabel how {@code outDir} is shown in messages, for example {@code src/main/java-generated}
 */
public record Layout(Path sqlDir, String sqlLabel, Path outDir, String outLabel) {}
