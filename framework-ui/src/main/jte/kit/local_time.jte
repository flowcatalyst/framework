@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.LocalTime p
!{var at = p.at() == null ? "" : java.time.format.DateTimeFormatter.ISO_INSTANT.format(p.at().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));}
@if(p.at() == null)${or(p.empty(), "-")}@else<time datetime="${at}" data-init="${"el.firstElementChild.textContent = new Date('" + at + "')." + (p.dateOnly() ? "toLocaleDateString" : "toLocaleString") + "(" + Props.LOCALE_EXPR + ")"}"><span data-ignore-morph>${p.dateOnly() ? java.time.LocalDate.ofInstant(p.at(), java.time.ZoneOffset.UTC).toString() : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(p.at()) + " UTC"}</span></time>@endif
