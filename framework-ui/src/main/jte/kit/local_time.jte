@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.LocalTime p
@if(p.at() == null)${or(p.empty(), "-")}@else<time datetime="${java.time.format.DateTimeFormatter.ISO_INSTANT.format(p.at().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))}" data-init="${p.dateOnly() ? "el.textContent = new Date(el.getAttribute('datetime')).toLocaleDateString()" : "el.textContent = new Date(el.getAttribute('datetime')).toLocaleString()"}">${p.dateOnly() ? java.time.LocalDate.ofInstant(p.at(), java.time.ZoneOffset.UTC).toString() : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(p.at()) + " UTC"}</time>@endif
