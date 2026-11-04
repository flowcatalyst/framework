/**
 * Languages and locale resolution for the server-rendered UI (contract {@code docs/contracts/i18n.md}):
 * flat JSON catalogs ({@link io.flowcatalyst.framework.ui.i18n.Catalog}), layered lookup with
 * fallback ({@link io.flowcatalyst.framework.ui.i18n.Bundle}), the messages of one request ({@link
 * io.flowcatalyst.framework.ui.i18n.Messages}) and the resolution of its locale ({@link
 * io.flowcatalyst.framework.ui.i18n.LocaleResolver}). No {@code ResourceBundle}.
 */
@NullMarked
package io.flowcatalyst.framework.ui.i18n;

import org.jspecify.annotations.NullMarked;
