<!-- types: Kit, Props, Markup, JsonPretty, MarkdownHtml, Views, Patches, UiAssets, UiModule, ViewCsp, Theme, Flash, Classes, AppShells, ShellBrand, Datastar, TestClient, RouteOptions, CsrfGuard, Response -->
# Server-rendered pages with JTE (optional)

> **Optional, and secondary to the Nuxt UI package.** The framework's UI is the shared Nuxt UI package
> (`@flowcatalyst/ui`, [static assets and the SPA](static-assets-and-spa.md)). `framework-ui` is for an
> application that wants server-rendered pages without a JavaScript build. Nothing else in the framework
> depends on it and the Nuxt UI demo (`examples/orders-demo`) does not use it; `examples/orders-demo-jte`
> is its own entry point. Built 2026-10-16 on owner approval (M-UI).

## When to use it, and when not

Use it when the application is mostly forms, lists and detail pages, nobody on the team wants a Node
toolchain, and the pages can live with a policy that allows `'unsafe-eval'` (below). The pages are
plain HTML that works without JavaScript (links, GET and POST forms, post/redirect/get); Datastar adds
the drawer, the dialogs and live patches on top.

Prefer the Nuxt UI package when:

- the pages show content a user can write (rich text, other people's markup): the strict policy of the
  SPA has no `'unsafe-eval'`, this module's cannot;
- the application needs client-side state, typed forms from the OpenAPI document, or the shared admin
  pages (`framework-admin` ships its pages in the Nuxt UI package only);
- you need components the kit does not specify (date pickers, charts, rich tables).

The kit's specs (`ui-kit/components`) are the behaviour checklist for both UIs. The Nuxt side does not
render this module's markup; this module renders exactly the kit's golden HTML.

## Adding it

One dependency (the BOM pins the versions: `jte-runtime` 3.2.4, the build plugin is the application's own):

```xml
<dependency>
  <groupId>io.flowcatalyst.framework</groupId>
  <artifactId>framework-ui</artifactId>
</dependency>
```

An application writing its own JTE pages adds the plugin that compiles them to Java during the build, so
nothing is compiled at run time and no template source is on the production classpath
(`examples/orders-demo-jte/pom.xml`):

```xml
<plugin>
  <groupId>gg.jte</groupId>
  <artifactId>jte-maven-plugin</artifactId>
  <version>${jte.plugin.version}</version>
  <configuration>
    <sourceDirectory>${project.basedir}/src/main/jte</sourceDirectory>
    <contentType>Html</contentType>
  </configuration>
  <executions>
    <execution>
      <id>generate-templates</id>
      <phase>generate-sources</phase>
      <goals><goal>generate</goal></goals>
    </execution>
  </executions>
</plugin>
```

The modules, from `OrdersJteDemo`:

```java
UiAssets assets = UiAssets.load();
return App.builder()
    .module(new UiModule(assets))
    .module(new OrdersUiModule(assets))
    .module(new JettyHttpModule());
```

`UiModule` serves the module's files at `/fw-ui/` (`kit.css`, the vendored Datastar 1.0.4 `datastar.js`) and
the icon sprite at `/icons/lucide-sprite.svg`, the path the specs and fixtures use. URLs carry a content
hash (`UiAssets.url("kit.css")`) and answer `If-None-Match` with 304.

## Components

`Kit` has one method per kit component and `Props` one record per component, named as the specs' Props
tables (`class` is `cls` and `for` is `forId`, because they are Java keywords). An absent prop is `null` or
`false`. A slot is a `gg.jte.Content`: `Markup.text("...")` for escaped text, `Markup.raw("...")` for markup
you vouch for, or a content block in a JTE template. Pages compose components in their own templates
(`order_row.jte` of the demo):

```
${Kit.tableRow(new Props.TR(false, false, "row-" + o.id(), null, null), @`
${Kit.tableCell(new Props.TD(0, null, null), @`<a href="/orders/${o.id()}" data-id="${o.id()}" data-on:click__prevent="${OrdersUi.OPEN_ORDER}">${o.customer()}</a>`)}
${Kit.tableCell(new Props.TD(0, null, null), @`${Kit.badge(new Props.Badge(o.badgeVariant(), null, null, null), @`${o.statusLabel()}`)}`)}
`)}
```

All 80 fixture directories of `ui-kit/fixtures` (268 cases) are covered: button, legacy button, icon button,
input, textarea, select, checkbox, switch, label, field, field group, form field, search input, filter
select, toggle group, badge, tag, alert, flash banner, flash message, flash stack, spinner, skeleton,
breadcrumb, empty state, page header, form section, detail field, code chips, code block, table and its
parts, sortable header, empty row, data table, row actions, table toolbar (and its state part),
pagination, cursor pager, paginator, local time, date range, tabs, dialog, confirm dialog, sheet,
drawer (and its content part), form dialog, autocomplete (and its options part), pick list, multi-select,
app shell, sidebar and user menu, and (added in GU3) tab list, password input, secret reference input,
permission-denied dialog, search select, markdown, code editor and diagram, and (added in GU4 step 2) the thirteen
components that had only a spec: accordion, alert dialog, avatar, card, dirty form, dropdown menu, hover card, JSON
block, kbd, progress, radio group, separator and tooltip. Not components of the kit and not
here: login pages (the platform hosts login), and anything the specs mark NP.

The app shell's `brand` (`Props.Brand`: name, logo URL, logo SVG, logo height; app-shell.md "Brand") is the last
component of `Props.AppShell`; the nine-argument constructor without it is kept and renders the FlowCatalyst bolt
as before. `ShellBrand` holds the rules the templates apply: the logo URL passes `Markup.url`, the SVG is written as
an `<img>` whose `src` is a `data:image/svg+xml;base64` URL the kit builds (never inline markup), and a height of 0
is 32. The shell's colours are the CSS custom properties `--fc-sidebar-background`, `--fc-topbar-background` and
`--fc-sidebar-accent`, whose fallbacks are the navy look (app-shell.md "Theming").

`FixtureConformanceTest.NOT_YET` (fixture directories without a renderer) is empty. A new fixture directory must
get its renderer in the same change, or be listed there first.

### The thirteen former spec-only components

`Kit.accordion(props, panels)` (one slot per item, by its `value`), `alertDialog`, `avatar`, `card(props, body,
footer)` (the content and footer regions render only when the slot writes something), `dirtyForm(props, body)`,
`dropdownMenu(props, trigger, items)`, `hoverCard(props, trigger, body)`, `jsonBlock`, `kbd`, `progress`,
`radioGroup`, `separator` and `tooltip(props, trigger)`. Props are the records of the same names
(`Props.Accordion`, `Props.AlertDialog`, ...; `Props.RadioOption`, `Props.NameValue`, `Props.AccordionItem` for
list entries). Unknown sizes, orientations, alignments, confirm variants and heading levels outside 1 to 6 are
refused; ids and the dirty form's signal are validated; the avatar's `src` and the alert dialog's `action` pass
the URL check.

`Kit.jsonBlock(new Props.JsonBlock(node, attrs, cls))` takes a Jackson `JsonNode` and prints it with
`JsonPretty` (2-space indent, `"key": value`, one member per line, `{}` and `[]` when empty, keys in the order
the node has them, no `\u003c` escapes) before the text is HTML-escaped. Jackson's own pretty printer writes
`"a" : 1`, so the module has a small printer of its own. `Props.Progress` takes `BigDecimal` numbers (an absent
`value` is the indeterminate bar, zero is a value).

### Markdown, the code editor and the diagram

`Kit.markdown(new Props.Markdown(source, cls))` renders CommonMark plus the GFM tables, strikethrough, task lists and
autolinks with heading ids (commonmark-java), then sanitises with the OWASP HTML sanitiser (`MarkdownHtml.html`):
raw HTML in the source, `script`, `iframe`, `svg`, forms, `on*` attributes, `javascript:` and `data:` URLs and any
attribute or class outside the policy are removed; kept are heading ids, `language-*` classes on `code`,
`text-align` on table cells, display-only task-list checkboxes, images, and links with `http`, `https`, `mailto`
or a relative URL. It may be given user data. A render failure shows the source in a `<pre>`.

The code editor and the diagram are islands: `Kit.codeEditor(new Props.CodeEditor(...))` is a `<textarea>` that
`fc-code-editor.js` replaces with CodeMirror, `Kit.diagram(new Props.Diagram(label, bundleUrl, cls), Markup.text(mermaidSource))`
is Mermaid source that `fc-diagram.js` draws. The components emit no `<script>`; the page names the island
(next section).

`Props.PermissionDeniedDialog` names no application path: `homeUrl` is required when the kind is `denied`, `loginUrl`
when it is `session` (an `IllegalArgumentException` while rendering otherwise); `PermissionDeniedDialog.kindOf(message)`
is the SPA's text rule. `Kit.searchSelect(new Props.SearchSelect(...))` is a single choice from a searchable list of
links (`ui-kit/components/search-select.md`); its `href` is a `Function<String, String>` of the option's value.

The pick list covers long role lists: `Props.PickItem` takes `key` (the id token, for a value that is not
id-safe), `group`, `description` and `locked` (shown ticked, counted, submitted through a hidden input, and not
removable), and `Props.PickList` takes `bulk` (Select shown, Clear) and `embedded` (the two panes only, for a
picker inside a form that already submits). `Props.MultiSelect` takes `chips`. The constructors without these
parts still exist. A `value` without a `key` must be id-safe and is refused otherwise; with a `key` it may be
any text.

### Escaping, and values that become code

- Every text and attribute value is HTML-escaped by JTE's HTML mode. `attrs` maps go through
  `Markup.attrs`, which escapes values and refuses an attribute name that is not a plain name or starts with
  `on` (an inline event handler is script from a string, and the page's policy forbids it).
- `href` and `action` go through `Markup.url`: relative references and `http`, `https`, `mailto`, `tel` pass;
  `javascript:`, `data:` and anything hidden by whitespace become `about:invalid#blocked`.
- Datastar attribute values are code. An id, a signal name or a path that enters an expression is validated
  (`Markup.id`, `Markup.signal`, `Markup.path`) and refused with `IllegalArgumentException`. User data goes
  in plain attributes (the demo's links carry the record id in `data-id` and read it with `el.dataset.id`),
  never in an expression. An unknown variant is an error, not a silent default.
- `Markup.raw` and the slots of fixtures are the only unescaped paths. Do not pass request data to them.

## Pages: one template, a full page or a patch

`Views` answers a navigation with the full document (head with the stylesheet and Datastar, the app shell
when the builder has one, the flash stack, the content) and a Datastar request (`Datastar-Request: true`)
with an event stream that patches the same content. The content is wrapped in `<div id="fw-page">`, which the
patch morphs. From `OrdersUiModule` and `OrdersUi`:

```java
Views.builder(assets).titleSuffix(" - Orders demo").shell(r -> shell()).build();
```

```java
return views.page(r, status, "Orders", Pages.orders(new OrdersView(q, l, form)), flashes);
```

A handler that answers more than the page streams patches (`Patches`: `outer`, `inner`, `append`,
`prepend`, `replace`, `remove`, `page`, `flash`, `signals`, `focus`). The create handler of the demo:

```java
p.outer(Pages.list(new OrdersView(q, l, NewOrderForm.empty())));
p.replace("#new-order", Pages.newOrderDialog(NewOrderForm.empty()));
p.flash("success", "Order created.", o.customer() + " (" + o.id() + ")");
p.focus("new-order-open");
```

The answering rules are the kit's (`ui-kit/components/_conventions.md`): a handled Datastar request always
answers `200` with at least one patch, an expected failure patches the error where it belongs (the form again,
with `aria-invalid` and the message linked by `aria-describedby`), mutations use `{retryMaxCount: 0}`. The
same routes answer a native form post: a validation failure re-renders the page with status `422`
(`Views.page(request, 422, ...)`), a success redirects `303` with a fixed flash key (`?flash=created`) that the
page maps to text on the server; an unknown key shows nothing, and text from a request is never a flash.

A page that uses an island lists its script by asset name; each becomes a `<script type="module">` in the head,
after Datastar (only JavaScript assets of `UiAssets` can be named; anything else is an `IllegalArgumentException`):

```java
return views.page(r, 200, "Editor", Markup.text("x"), List.of(), List.of("fc-code-editor.js", "fc-diagram.js"));
```

`UiAssets` serves the islands (`fc-code-editor.js`, `fc-diagram.js`, `mermaid.min.js`, the last only fetched by a
page that draws a diagram) and the licence notices the third-party files travel with; the pins of the Datastar,
Mermaid and island files are checked by `ViewsTest`.

`Views.Builder.sessionExpiredLogin(path)` sends a user whose Datastar action was answered `401` to
`path?returnTo=<current page>` (sessions contract 4.1); `sessionExpiredLogin(path, param)` names another parameter
for an application with its own sign-in. `Patches.redirect(path)` and `Patches.replaceURL(path)` navigate or set the
address bar by a hidden `data-init` element (no inline script); the path must be local (one leading `/`, not `//`
or `/\`, no control character or backslash, `Markup.isLocalPath`), otherwise they throw `IllegalArgumentException`.

### Native form posts: form routes, `303` and the flash cookie

A route a page posts to natively is declared `RouteOptions.acceptForms()` (or `.acceptingForms()` on other options):
the sessions CSRF guard (`CsrfGuard.forSessions`) then also accepts `application/x-www-form-urlencoded` and
`multipart/form-data` bodies on it. Fetch Metadata and the `Origin` check run first and are unchanged; every other
route stays JSON-only (`415`), the public guard (`CsrfGuard.publicForm`) stays JSON-only on every route, and bearer
requests are not checked at all. A re-rendered form answers `422` with the page; success calls
`views.seeOther(r, "/orders?x=1", "saved")`: `303` with `Location` and a `fw_flash` cookie, or, for a Datastar
request (which cannot follow a `303`), `200` with the cookie and a redirect patch.

The cookie holds a key, never text. Register the messages when building the views and the next document shows the
message in its flash stack and clears the cookie:

```java
Views.builder(ASSETS).flash("saved", new Props.FlashMessage("success", "Saved.", "The order was saved.", false, null)).build();
```

A key must be `[a-z0-9-]{1,64}` and registered, or `setFlash`/`seeOther` throw `IllegalArgumentException` (nothing is
sent); an unknown key in a request cookie renders nothing. The cookie is `Path=/; HttpOnly; SameSite=Lax;
Max-Age=60` and `Secure`; call `Views.Builder.plainHttpCookies()` only when the application runs over plain HTTP
in local or test (this module cannot see the connection, so it cannot decide per request as the Go module does).

POST handlers need a same-origin check, because a Datastar action carries no token; the demo's
`OrdersUi.requireSameOrigin` reads `Sec-Fetch-Site`, then `Origin`. A real application also authorises
every handler (see [sessions and login](sessions-and-login.md)); the demo has no sign-in.

### A modal dialog the answer must close

Replace it (`Patches.replace`), do not morph it. Datastar's morph removes the `open` attribute but leaves a
`showModal()` dialog in the top layer, which keeps the page inert (found in the browser check; the kit's
form-dialog spec says to close it with a self-removing `data-init` element, which runs after a morph in the
same answer, `Patches.closeDialog` is kept for answers that do not re-render the dialog).

## The page policy

`ViewCsp.policy(...)` is the strict policy of `Csp.strict` with two differences:

- `script-src` adds `'unsafe-eval'`: Datastar compiles each `data-*` expression with the `Function`
  constructor (its 1.0.4 bundle has no CSP-safe build). The browser check proves it: with
  `'unsafe-eval'` removed the drawer cannot open and the console reports the violation.
- `style-src-attr 'unsafe-inline'`: the kit's markup has a few `style` attributes (a panel hidden until
  Datastar runs, the select's check-mark, the logo size). `<style>` and external styles stay `'self'`.

There is no inline script and no `<style>` element, so no nonce is needed; `Views.Builder.scriptHash` allows an
application's own inline script by hash. The headers `Cross-Origin-Opener-Policy`, `Permissions-Policy` and
`nosniff` go with it. The weaker script policy is the main security reason to prefer the Nuxt UI package for
untrusted content.

## Theme, icons and the stylesheet

`kit.css` is the Tailwind 4.3.2 build of `ui-kit/theme` plus the classes this module and the demo use. It is
committed (`src/main/resources/fw-ui/kit.css`); Maven never runs Node or Tailwind. When a template gains a
class or the theme changes, run `framework-ui/assets/build.sh` (it uses the ui-kit's standalone CLI) and commit
the result; an application with its own templates writes its own input file that imports `ui-kit/theme` and adds
its `@source` lines (`framework-ui/assets/kit.css` is the model). Dark mode is a `dark` class on `<html>`
rendered by the server from the `fc_theme` and `fc_scheme` cookies (`Theme`), the sidebar's collapsed state
from `fc_sidebar`. Icons reference `/icons/lucide-sprite.svg#i-<name>`; `Kit.withSpriteUrl` overrides the base.

## Tests, and what they prove

- `FixtureConformanceTest` renders every fixture (strict props: an unknown name fails) and compares it with the
  golden after the kit's normalisation (`ui-kit/fixtures/README.md`); the list of fixture directories without
  a renderer (`NOT_YET`, empty) is asserted exact, both ways. A `search-select` fixture gives `href` as a string with
  `{value}` for the option's value, which the test adapter turns into the function.
- `XssSweepTest` puts an XSS payload into every string prop of every fixture that has a renderer, one at a time: the component
  refuses it (a validated id, signal, path or variant) or the output has no script or image element and no event
  handler beyond the fixture's own (markdown may keep a sanitised image, never a script or a handler).
- `EscapingTest` and `ViewsTest` cover URL schemes, `attrs`, the policy, the full-page or patch switch, assets and
  cookies. Mutants checked by hand (each fails a test): `Markup.escape` returning its input; a template
  writing a text, an attribute, a textarea body, an alert description or a breadcrumb label with `$unsafe`; the
  `on*` name guard removed; `Markup.id` unvalidated; `Markup.url` letting every scheme through; the
  Datastar test always false or always true; `unsafe-inline` added to `script-src`.
- `examples/orders-demo-jte` tests the pages in process with `TestClient` (list state in every link, drawer,
  validation as patches, 422 and 303 for native posts, cross-site refusal, escaping of a hostile customer name).
- `examples/orders-demo-jte/e2e/browser-check.mjs` drives the demo in Chromium with the Playwright and axe packages
  of the `ui/` workspace (nothing installed for it): Datastar runs, the drawer, dialog and cancel flow work, no
  `securitypolicyviolation`, dark mode, no-JavaScript pages, and axe. Run it against a demo you start yourself
  on a free port (`HTTP_PORT=<port> java -cp ... OrdersJteDemo`, then `BASE_URL=http://127.0.0.1:<port> node
  e2e/browser-check.mjs`). It is not part of `mvn verify` (it needs a browser and a running server).

### Known findings in the kit (axe, not this module)

axe-core 4.13 in Chromium reports serious findings that come from the kit's spec and theme, which the platform's
templ port shares: `aria-label` on the disabled paginator `<span>`s (paginator.md), and colour contrast of white
on `--primary` (3.35:1), the success and info badges (and the destructive badge in dark mode), the page subtitle
and the outline Filters button. The browser check pins them as a ratchet (a new rule or more nodes fails).
Fixing them means changing the kit's tokens or specs and fixtures together; that is the owner's call.

## Not in this module

A login page, a CSRF token helper, a rich-text or chart component, and a JTE-sources jar for
applications that want to call kit templates by `@template` (they call `Kit` from Java or from a template, as
above). Kit templates are not hot-reloaded: the build compiles them.
