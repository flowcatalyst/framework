@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param String current
<div class="fc-theme-choice" role="group" aria-label="Theme">
@for(var o : Theme.choices())<button type="button" class="fc-theme-option" aria-pressed="${o.pressed(current)}" data-attr:aria-pressed="${o.pressedExpr()}" data-on:click="${o.click()}">@template.kit.icon(name = o.icon(), cls = "size-[0.95rem]") ${o.label()}</button>@endfor
</div>
