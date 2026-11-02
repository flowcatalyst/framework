@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.DropdownMenu p
@param gg.jte.Content trigger = null
@param gg.jte.Content items = null
!{var align = Classes.oneOf(p.align(), "left", "dropdown menu align", "left", "right");}
<details id="${id(p.id())}" class="${cx("group relative inline-block", p.cls())}" data-on:click__window="el.open && !el.contains(evt.target) && (el.open = false)" data-on:keydown__window="evt.key === 'Escape' && el.open && (el.open = false, el.querySelector('summary').focus())">
<summary class="cursor-pointer list-none [&::-webkit-details-marker]:hidden">@if(trigger != null)${trigger}@endif</summary>
<div class="${"absolute z-50 min-w-40 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-sm " + ("right".equals(align) ? "top-full right-0 mt-1" : "top-full left-0 mt-1")}">@if(items != null)${items}@endif</div>
</details>
