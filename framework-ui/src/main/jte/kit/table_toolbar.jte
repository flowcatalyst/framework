@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.TableToolbar p
@param gg.jte.Content filters = null
<form id="${id(p.id())}" method="get" action="${p.action()}" class="fc-table-toolbar"$unsafe{attrs(p.formAttrs())}>
<div class="fc-toolbar-start">
@if(!p.hideSearch())<div class="fc-input-icon fc-toolbar-search">
@template.kit.icon(name = "search", cls = "size-[1rem]")
<input type="search" name="q" class="fc-input" placeholder="${p.searchPlaceholder()}" aria-label="${p.searchPlaceholder()}" value="${nz(p.searchValue())}"$unsafe{attrs(p.searchAttrs())}>
</div>@endif
</div>
<div class="fc-toolbar-end">
@if(p.size() != null)<select name="size" class="fc-select w-24" title="Result size" aria-label="Result size" data-on:change="${SUBMIT_ON_CHANGE}">
@if(p.size().options() != null)@for(int n : p.size().options())<option value="${n}" selected="${n == p.size().selected()}">${n}</option>@endfor@endif
</select>@endif
@if(!p.hideRefresh())<button type="submit" class="fc-icon-btn" title="Refresh" aria-label="Refresh">@template.kit.icon(name = "refresh-cw", cls = "size-[1rem]")</button>@endif
@template.kit.table_toolbar_state(p = p.state(filters != null))
@if(filters != null)<div id="${p.id() + "-filters"}" popover class="fc-filter-popover" data-on:toggle__capture="${POPOVER_ANCHOR}">
<div class="fc-filter-popover-body">${filters}</div>
</div>@endif
</div>
</form>
