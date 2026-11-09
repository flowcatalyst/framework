@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.AppShell p
@param gg.jte.Content body = null
@template.kit.app_sidebar(p = p)
@template.kit.user_menu(p = p)
<div class="fc-sidebar-backdrop" aria-hidden="true" data-on:click="${AppShells.MOBILE_CLOSE}" data-on:keydown__window="${AppShells.MOBILE_ESCAPE}"></div>
<div class="fc-main">
<header class="fc-topbar">
<button type="button" id="fc-topbar-menu" class="fc-topbar-menu" aria-label="${Kit.t("kit.appshell.open_nav")}" aria-controls="fc-sidebar" aria-expanded="false" data-attr:aria-expanded="$sidebarMobileOpen ? 'true' : 'false'" data-on:click="${AppShells.MOBILE_OPEN}">@template.kit.icon(name = "menu", cls = "size-[1.25rem]")</button>
!{var b = p.brand();}@if(b != null && ShellBrand.hasImage(b))<a href="${url(p.logoHref())}" class="fc-topbar-logo fc-topbar-logo-image"><img src="${ShellBrand.imageSrc(b)}" alt="${ShellBrand.imageAlt(b)}"></a>@else<a href="${url(p.logoHref())}" class="fc-topbar-logo">@template.kit.logo(label = ShellBrand.boltLabel(b))</a>@endif
</header>
<main class="p-[24px]">@if(body != null)${body}@endif</main>
</div>
