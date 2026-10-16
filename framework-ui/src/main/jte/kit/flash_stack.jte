@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.FlashStack p
@param gg.jte.Content body = null
<div id="${id(p.id())}" class="fc-flash-stack" aria-live="polite" data-on:datastar-fetch="${Flash.FALLBACK_EXPR}">
@for(var f : Flash.fallbacks())<template data-flash-template="${f.key()}">@template.kit.flash_message(p = f.message())</template>
@endfor
@if(body != null)${body}@endif
</div>
