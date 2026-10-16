@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.RowActions p
!{var actions = p.actions() == null ? java.util.List.<Props.RowAction>of() : p.actions(); var menu = p.menu() == null ? java.util.List.<Props.RowAction>of() : p.menu();}
<td class="${cx("p-3 align-middle whitespace-nowrap", "w-px text-right")}"$unsafe{attrs(p.attrs())}>
<div class="flex items-center justify-end gap-1">
@for(int i = 0; i < actions.size(); i++)!{var a = actions.get(i); var inline = a.destructive() ? "fc-icon-btn fc-icon-btn-danger" : "fc-icon-btn";}
@if(nz(a.disabledReason()) != null)<button type="button" class="${inline}" title="${a.label() + ": " + a.disabledReason()}" aria-label="${p.withRow(a.label())}" aria-disabled="true" aria-describedby="${p.partId("a", i)}">@template.kit.icon(name = a.icon(), cls = "size-[1rem]")</button>
<span id="${p.partId("a", i)}" hidden>${a.disabledReason()}</span>
@elseif(nz(a.href()) != null)<a href="${a.href()}" class="${inline}" title="${a.label()}" aria-label="${p.withRow(a.label())}"$unsafe{attrs(a.attrs())}>@template.kit.icon(name = a.icon(), cls = "size-[1rem]")</a>
@else<button type="button" class="${inline}" title="${a.label()}" aria-label="${p.withRow(a.label())}"$unsafe{attrs(a.attrs())}>@template.kit.icon(name = a.icon(), cls = "size-[1rem]")</button>
@endif
@endfor
@if(!menu.isEmpty())<button type="button" class="fc-icon-btn" title="More actions" aria-label="${p.moreLabel()}" popovertarget="${p.menuId()}">@template.kit.icon(name = "ellipsis", cls = "size-[1rem]")</button>
<div id="${p.menuId()}" popover class="fc-row-menu" data-on:toggle__capture="${POPOVER_ANCHOR}">
@for(int i = 0; i < menu.size(); i++)!{var a = menu.get(i); var item = a.destructive() ? "fc-menu-item danger" : "fc-menu-item";}
@if(p.separatorBefore(i))<div role="separator" class="fc-menu-separator"></div>@endif
@if(nz(a.disabledReason()) != null)<button type="button" class="${item}" aria-disabled="true" aria-label="${a.label()}" aria-describedby="${p.partId("m", i)}">@template.kit.icon(name = a.icon(), cls = "size-[1rem]")<span>${a.label()}<span id="${p.partId("m", i)}" class="fc-menu-hint">${a.disabledReason()}</span></span></button>
@elseif(nz(a.href()) != null)<a href="${a.href()}" class="${item}" data-on:click__capture="${CLOSE_MENU}"$unsafe{attrs(a.attrs())}>@template.kit.icon(name = a.icon(), cls = "size-[1rem]")<span>${a.label()}</span></a>
@else<button type="button" class="${item}" data-on:click__capture="${CLOSE_MENU}"$unsafe{attrs(a.attrs())}>@template.kit.icon(name = a.icon(), cls = "size-[1rem]")<span>${a.label()}</span></button>
@endif
@endfor
</div>
@endif
</div>
</td>
