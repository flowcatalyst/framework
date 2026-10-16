@import io.flowcatalyst.framework.examples.ordersjte.*
@import io.flowcatalyst.framework.ui.kit.*
@import java.util.List
@param Order o

${Kit.breadcrumb(new Props.Breadcrumb(List.of(new Props.Crumb("Orders", "/orders"), new Props.Crumb(o.id(), null))))}
${Kit.pageHeader(new Props.PageHeader(o.customer(), o.id()), @`${Kit.badge(new Props.Badge(o.badgeVariant(), null, null, null), @`${o.statusLabel()}`)}`)}
${Kit.formSection(new Props.FormSection("Details", null, false, true), @`<div class="fc-detail-grid">
${Kit.detailField(new Props.DetailField("Total", o.total(), false), null)}
${Kit.detailField(new Props.DetailField("Placed", null, false), @`${Kit.localTime(new Props.LocalTime(o.createdAt(), false, null))}`)}
</div>`, null)}
