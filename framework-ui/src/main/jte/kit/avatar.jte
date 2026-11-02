@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Avatar p
!{var size = Classes.oneOf(p.size(), "md", "avatar size", "sm", "md", "lg"); var src = url(p.src());}
<span class="${cx("relative flex shrink-0 overflow-hidden rounded-full", "sm".equals(size) ? "size-8 text-xs" : "lg".equals(size) ? "size-12 text-base" : "size-10 text-sm", p.cls())}">@if(src != null)<img src="${src}" alt="${or(p.alt(), "")}" class="absolute inset-0 size-full object-cover">@endif<span class="flex size-full items-center justify-center bg-foreground/10 font-medium text-foreground select-none">${p.initials()}</span></span>
