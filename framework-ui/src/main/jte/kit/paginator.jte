@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Paginator p
<nav id="${nz(p.id())}" class="fc-paginator" aria-label="${Kit.t("kit.paginator.label")}">
@if(p.currentPage() > 1)<a class="fc-page-link" href="${url(p.href(1))}" aria-label="${Kit.t("kit.paginator.first")}"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevrons-left", cls = "size-[1rem]")</a>
<a class="fc-page-link" href="${url(p.href(p.currentPage() - 1))}" aria-label="${Kit.t("kit.paginator.previous")}"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevron-left", cls = "size-[1rem]")</a>
@else<span class="fc-page-link" aria-disabled="true" aria-label="${Kit.t("kit.paginator.first")}">@template.kit.icon(name = "chevrons-left", cls = "size-[1rem]")</span>
<span class="fc-page-link" aria-disabled="true" aria-label="${Kit.t("kit.paginator.previous")}">@template.kit.icon(name = "chevron-left", cls = "size-[1rem]")</span>
@endif
@for(int n : p.window())@if(n == p.currentPage())<span class="fc-page-link" aria-current="page">${n}</span>@else<a class="fc-page-link" href="${url(p.href(n))}"$unsafe{attrs(p.linkAttrs())}>${n}</a>@endif@endfor
@if(p.currentPage() < p.pageCount())<a class="fc-page-link" href="${url(p.href(p.currentPage() + 1))}" aria-label="${Kit.t("kit.paginator.next")}"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevron-right", cls = "size-[1rem]")</a>
<a class="fc-page-link" href="${url(p.href(p.pageCount()))}" aria-label="${Kit.t("kit.paginator.last")}"$unsafe{attrs(p.linkAttrs())}>@template.kit.icon(name = "chevrons-right", cls = "size-[1rem]")</a>
@else<span class="fc-page-link" aria-disabled="true" aria-label="${Kit.t("kit.paginator.next")}">@template.kit.icon(name = "chevron-right", cls = "size-[1rem]")</span>
<span class="fc-page-link" aria-disabled="true" aria-label="${Kit.t("kit.paginator.last")}">@template.kit.icon(name = "chevrons-right", cls = "size-[1rem]")</span>
@endif
@if(nz(p.formId()) != null)<select name="rows" form="${p.formId()}" class="fc-select ml-2" aria-label="${Kit.t("kit.paginator.rows_per_page")}" data-on:change="el.form.requestSubmit()">
@for(int n : p.rowChoices())<option value="${n}" selected="${n == p.rowCount()}">${n}</option>@endfor
</select>@endif
<span class="fc-page-report">${p.report()}</span>
</nav>
