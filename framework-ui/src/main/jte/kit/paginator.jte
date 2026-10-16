@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Paginator p
<nav id="${nz(p.id())}" class="fc-paginator" aria-label="Pagination">
@if(p.currentPage() > 1)<a class="fc-page-link" href="${p.href(1)}" aria-label="First"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevrons-left", cls = "size-[1rem]")</a>
<a class="fc-page-link" href="${p.href(p.currentPage() - 1)}" aria-label="Previous"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevron-left", cls = "size-[1rem]")</a>
@else<span class="fc-page-link" aria-disabled="true" aria-label="First">@template.kit.icon(name = "chevrons-left", cls = "size-[1rem]")</span>
<span class="fc-page-link" aria-disabled="true" aria-label="Previous">@template.kit.icon(name = "chevron-left", cls = "size-[1rem]")</span>
@endif
@for(int n : p.window())@if(n == p.currentPage())<span class="fc-page-link" aria-current="page">${n}</span>@else<a class="fc-page-link" href="${p.href(n)}"$unsafe{attrs(p.linkAttrs())}>${n}</a>@endif@endfor
@if(p.currentPage() < p.pageCount())<a class="fc-page-link" href="${p.href(p.currentPage() + 1)}" aria-label="Next"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevron-right", cls = "size-[1rem]")</a>
<a class="fc-page-link" href="${p.href(p.pageCount())}" aria-label="Last"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevrons-right", cls = "size-[1rem]")</a>
@else<span class="fc-page-link" aria-disabled="true" aria-label="Next">@template.kit.icon(name = "chevron-right", cls = "size-[1rem]")</span>
<span class="fc-page-link" aria-disabled="true" aria-label="Last">@template.kit.icon(name = "chevrons-right", cls = "size-[1rem]")</span>
@endif
@if(nz(p.formId()) != null)<select name="rows" form="${p.formId()}" class="fc-select ml-2" aria-label="Rows per page" data-on:change="el.form.requestSubmit()">
@for(int n : p.rowChoices())<option value="${n}" selected="${n == p.rowCount()}">${n}</option>@endfor
</select>@endif
<span class="fc-page-report">${p.report()}</span>
</nav>
