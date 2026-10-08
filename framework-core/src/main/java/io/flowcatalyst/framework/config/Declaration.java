package io.flowcatalyst.framework.config;

/**
 * A setting together with the module that declared it.
 *
 * @param module id of the declaring module
 * @param setting the setting
 */
public record Declaration(String module, Setting<?> setting) {}
