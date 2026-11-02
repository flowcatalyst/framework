@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Separator p
!{var vertical = "vertical".equals(Classes.oneOf(p.orientation(), "horizontal", "separator orientation", "horizontal", "vertical"));}
@if(vertical)<hr aria-orientation="vertical" class="${cx("shrink-0 border-0 bg-border h-full w-px", p.cls())}">@else<hr class="${cx("shrink-0 border-0 bg-border h-px w-full", p.cls())}">@endif
