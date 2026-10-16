@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.CodeChips p
!{var segs = (p.code() == null ? "" : p.code()).split(":", -1); var kinds = new String[] {"app", "subdomain", "aggregate", "event"};}
<span class="${cx("fc-code", p.cls())}">@for(int i = 0; i < segs.length; i++)@if(i > 0)<span class="fc-code-sep">:</span>@endif<span class="${"fc-code-seg " + kinds[Math.min(i, 3)]}"$unsafe{attrs(p.segmentAttrs() != null && i < p.segmentAttrs().size() ? p.segmentAttrs().get(i) : null)}>${segs[i]}</span>@endfor</span>
