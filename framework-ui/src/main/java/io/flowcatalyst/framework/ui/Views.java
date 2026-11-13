package io.flowcatalyst.framework.ui;

import gg.jte.Content;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.datastar.Datastar;
import io.flowcatalyst.framework.ui.i18n.Bundle;
import io.flowcatalyst.framework.ui.i18n.LocaleResolver;
import io.flowcatalyst.framework.ui.i18n.Messages;
import io.flowcatalyst.framework.ui.kit.Kit;
import io.flowcatalyst.framework.ui.kit.Markup;
import io.flowcatalyst.framework.ui.kit.Props;
import io.flowcatalyst.framework.ui.kit.Theme;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The view response helper: one page template answers a browser navigation with a full HTML
 * document and a Datastar request with an element patch of the same content, so a link, a form post
 * and a live update all use one template.
 *
 * <pre>{@code
 * Views views = Views.builder(assets).titleSuffix(" - Orders").shell(r -> shellFor(r)).build();
 * routes.get("/orders", r -> views.page(r, "Orders", ordersPage(listing(r))));
 * }</pre>
 *
 * <p>The page content is wrapped in {@code <div id="fw-page">}. A navigation gets the document
 * (head with the stylesheet and Datastar, the app shell when the builder has one, the flash stack,
 * then the content) with the page policy ({@link ViewCsp}) and security headers; a request that
 * carries {@code Datastar-Request: true} gets a {@code text/event-stream} whose one patch morphs
 * {@code #fw-page}. A Datastar answer is always {@code 200} (Datastar applies no other status).
 */
public final class Views {

  /** The id of the element the page content is wrapped in; a Datastar page answer morphs it. */
  public static final String PAGE_ID = "fw-page";

  /** The id of the flash stack in the shell. */
  public static final String FLASH_STACK_ID = "flash-stack";

  /**
   * The cookie that carries a flash message across a redirect (contract section 9). It holds a
   * key, never message text: the application maps keys to messages in code ({@link
   * Builder#flash}).
   */
  public static final String FLASH_COOKIE = "fw_flash";

  private static final int FLASH_MAX_AGE_SECONDS = 60;
  private static final Pattern FLASH_KEY = Pattern.compile("[a-z0-9-]{1,64}");

  private final UiAssets assets;
  private final String titleSuffix;
  private final @Nullable Function<Request, Props.AppShell> shell;
  private final @Nullable String loginPath;
  private final String loginParam;
  private final Map<String, Props.FlashMessage> flashes;
  private final boolean plainCookies;
  private final String csp;
  private final LocaleResolver locales;
  private final Bundle bundle;

  private Views(Builder b) {
    this.locales = b.locales;
    this.bundle = b.bundle;
    this.assets = b.assets;
    this.titleSuffix = b.titleSuffix;
    this.shell = b.shell;
    this.loginPath = b.loginPath;
    this.loginParam = b.loginParam;
    this.flashes = Map.copyOf(b.flashes);
    this.plainCookies = b.plainCookies;
    this.csp = ViewCsp.policy(b.scriptHashes, b.imageSources);
  }

  /**
   * Starts a builder.
   *
   * @param assets the assets the pages reference
   * @return the builder
   */
  public static Builder builder(UiAssets assets) {
    return new Builder(assets);
  }

  /** Builds {@link Views}. */
  public static final class Builder {
    private final UiAssets assets;
    private String titleSuffix = "";
    private @Nullable Function<Request, Props.AppShell> shell;
    private @Nullable String loginPath;
    private String loginParam = Props.Document.DEFAULT_LOGIN_PARAM;
    private final Map<String, Props.FlashMessage> flashes = new LinkedHashMap<>();
    private boolean plainCookies;
    private final List<String> scriptHashes = new ArrayList<>();
    private final List<String> imageSources = new ArrayList<>();
    private LocaleResolver locales = LocaleResolver.englishOnly();
    private Bundle bundle = Bundle.kit();

    private Builder(UiAssets assets) {
      this.assets = assets;
    }

    /**
     * Text appended to every page title.
     *
     * @param suffix for example {@code " - Orders"}
     * @return this
     */
    public Builder titleSuffix(String suffix) {
      this.titleSuffix = suffix;
      return this;
    }

    /**
     * Wraps pages in the app shell (sidebar, user menu, top bar) built from the request.
     *
     * @param shell the shell for a request
     * @return this
     */
    public Builder shell(Function<Request, Props.AppShell> shell) {
      this.shell = shell;
      return this;
    }

    /**
     * Sends a user whose session expired (a Datastar action answered 401) to this local path, with
     * {@code ?returnTo=} the current page (sessions contract 4.1).
     *
     * @param path for example {@code /auth/login}
     * @return this
     * @throws IllegalArgumentException unless the path matches {@code /[A-Za-z0-9/_-]*} and does
     *     not start with {@code //}
     */
    public Builder sessionExpiredLogin(String path) {
      return sessionExpiredLogin(path, Props.Document.DEFAULT_LOGIN_PARAM);
    }

    /**
     * As {@link #sessionExpiredLogin(String)}, naming the query parameter that carries the page to
     * return to, for an application whose own sign-in uses another one.
     *
     * @param path for example {@code /auth/login}
     * @param param starts with a letter, then letters, digits and {@code _}
     * @return this
     * @throws IllegalArgumentException for a path or parameter that is not plain
     */
    public Builder sessionExpiredLogin(String path, String param) {
      this.loginPath = Markup.path(path);
      this.loginParam = Props.Document.loginParam(param);
      return this;
    }

    /**
     * Registers the message a flash key stands for (contract section 9). The cookie carries only
     * the key, so no request data is ever rendered as a message.
     *
     * @param key {@code [a-z0-9-]{1,64}}
     * @param message the message shown on the next document (its kind is success, info, warn or
     *     error)
     * @return this
     * @throws IllegalArgumentException for a key that does not match, or an unknown kind
     */
    public Builder flash(String key, Props.FlashMessage message) {
      if (!FLASH_KEY.matcher(key).matches()) {
        throw new IllegalArgumentException("a flash key must match [a-z0-9-]{1,64}");
      }
      io.flowcatalyst.framework.ui.kit.Flash.kind(message.kind());
      this.flashes.put(key, message);
      return this;
    }

    /**
     * Resolves the locale of every request the views render (contract i18n.md section 3): the
     * document's {@code lang} and the kit's texts follow it. Without this, pages are English.
     *
     * @param locales the resolver, with the languages the application supports
     * @return this
     */
    public Builder locales(LocaleResolver locales) {
      this.locales = locales;
      return this;
    }

    /**
     * The catalogs the pages use: the kit's, with the application's layered on top ({@link
     * Bundle#with}). Default: the kit's own.
     *
     * @param bundle the bundle
     * @return this
     */
    public Builder catalogs(Bundle bundle) {
      this.bundle = bundle;
      return this;
    }

    /**
     * Lets the flash cookie go without {@code Secure}. Only for an application running over plain
     * HTTP in local or test (the same condition as an {@code http://} public base URL): this
     * module cannot see the connection (TLS ends in front of the server), so without this call the
     * cookie is always {@code Secure}.
     *
     * @return this
     */
    public Builder plainHttpCookies() {
      this.plainCookies = true;
      return this;
    }

    /**
     * Allows an inline script of the application by its hash. The module itself uses none.
     *
     * @param hash {@code sha256-...}, without quotes
     * @return this
     */
    public Builder scriptHash(String hash) {
      this.scriptHashes.add(hash);
      return this;
    }

    /**
     * Lets pages load images from another host, for example a map's tile server: the source is
     * added to the policy's {@code img-src} after {@code 'self' data:} (contract section 6 rule 1,
     * amended 2026-11-12). Nothing else in the policy changes. Call it once per host; a host given
     * twice appears once, where it was first given.
     *
     * @param source an {@code https} origin, {@code https://host} or {@code https://host:port},
     *     optionally with one leading wildcard label ({@code https://*.example.org}), with nothing
     *     after the host or port; checked by {@link #build()} ({@link ViewCsp#policy(List, List)})
     * @return this
     */
    public Builder imageSource(String source) {
      this.imageSources.add(source);
      return this;
    }

    /**
     * Builds the helper.
     *
     * @return the views
     * @throws IllegalArgumentException for a malformed script hash or image source
     */
    public Views build() {
      return new Views(this);
    }
  }

  /**
   * The messages for a request: its resolved locale over the views' catalogs. An application
   * translating its own texts in a handler uses {@code views.messages(request).t(key)}.
   *
   * @param request the request
   * @return the messages
   */
  public Messages messages(Request request) {
    return bundle.messages(locales.resolve(request));
  }

  /**
   * Whether the request came from a Datastar action.
   *
   * @param request the request
   * @return true when it carries {@code Datastar-Request: true}
   */
  public static boolean isDatastar(Request request) {
    return "true".equalsIgnoreCase(request.header("Datastar-Request"));
  }

  /**
   * A page: the full document for a navigation, a patch of {@code #fw-page} for a Datastar request.
   *
   * @param request the request
   * @param title the page title (without the suffix)
   * @param content the page content
   * @return the response
   */
  public Response page(Request request, String title, Content content) {
    return page(request, 200, title, content);
  }

  /**
   * A page with a status for the navigation case (a re-rendered form is {@code 422}); a Datastar
   * answer is {@code 200} whatever the status.
   *
   * @param request the request
   * @param status the status of the full document
   * @param title the page title (without the suffix)
   * @param content the page content
   * @return the response
   */
  public Response page(Request request, int status, String title, Content content) {
    return page(request, status, title, content, List.of());
  }

  /**
   * A page with flash messages rendered into its flash stack (the flash after a redirect: map a
   * fixed key to text on the server, never render text from the request). A Datastar answer ignores
   * them; patch a message with {@link Patches#flash}.
   *
   * @param request the request
   * @param status the status of the full document
   * @param title the page title (without the suffix)
   * @param content the page content
   * @param flashes the messages to show
   * @return the response
   */
  public Response page(
      Request request, int status, String title, Content content, List<Props.FlashMessage> flashes) {
    return page(request, status, title, content, flashes, List.of());
  }

  /**
   * A page that uses island scripts (the code editor, the diagram): each name becomes a {@code
   * <script type="module">} in the head of the document, after Datastar. A Datastar answer ignores
   * them (the document already loaded them).
   *
   * @param request the request
   * @param status the status of the full document
   * @param title the page title (without the suffix)
   * @param content the page content
   * @param flashes the messages to show, after the one a flash cookie carried
   * @param scripts asset names such as {@code fc-code-editor.js}; each must be a JavaScript asset
   *     of {@link UiAssets}
   * @return the response
   * @throws IllegalArgumentException for a name that is not a JavaScript asset
   */
  public Response page(
      Request request,
      int status,
      String title,
      Content content,
      List<Props.FlashMessage> flashes,
      List<String> scripts) {
    List<String> urls = scriptUrls(scripts);
    Content wrapped = wrap(content);
    if (isDatastar(request)) {
      return stream(request, patches -> patches.outer(wrapped));
    }
    return document(request, status, title, wrapped, flashes, urls);
  }

  /** The URLs of the named island scripts; Datastar and repeats are loaded once. */
  private List<String> scriptUrls(List<String> names) {
    List<String> urls = new ArrayList<>();
    for (String name : names) {
      if (!name.endsWith(".js") || !assets.names().contains(name)) {
        throw new IllegalArgumentException(
            "script " + Markup.escape(name) + " is not a JavaScript asset (assets: " + assets.names() + ")");
      }
      String url = assets.url(name);
      if (!name.equals("datastar.js") && !urls.contains(url)) {
        urls.add(url);
      }
    }
    return urls;
  }

  // ---- flash after a redirect (contract section 9) ------------------------------------------------

  /**
   * Queues the message registered under the key for the next document this browser is sent: adds the
   * {@code fw_flash} cookie to the response (call it on the {@code 303}). The key is a programming
   * constant, never request data.
   *
   * @param response the response to add the cookie to
   * @param key a key registered with {@link Builder#flash}
   * @return the response with the cookie
   * @throws IllegalArgumentException for a key that is not {@code [a-z0-9-]{1,64}} or was not
   *     registered: it would render nothing, so a typo is refused instead of skipped
   */
  public Response setFlash(Response response, String key) {
    if (!FLASH_KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("a flash key must match [a-z0-9-]{1,64}");
    }
    if (!flashes.containsKey(key)) {
      throw new IllegalArgumentException("flash key " + Markup.escape(key) + " is not registered (Views.Builder.flash)");
    }
    return response.addHeader("Set-Cookie", flashCookie(key, FLASH_MAX_AGE_SECONDS));
  }

  /**
   * Answers a native form post that succeeded: {@code 303} to a local path, carrying the flash with
   * this key when it is not null. A Datastar request cannot follow a {@code 303} the way a browser
   * does, so it gets the same outcome as an answer: the flash cookie and a stream with a {@link
   * Patches#redirect} patch (status {@code 200}).
   *
   * @param request the request
   * @param path a local path (contract section 8 rule 3), query allowed
   * @param flashKey a key registered with {@link Builder#flash}, or null for none
   * @return the response
   * @throws IllegalArgumentException for a path that is not local, or a key that is not registered;
   *     nothing is sent
   */
  public Response seeOther(Request request, String path, @Nullable String flashKey) {
    Markup.localPath(path);
    Response answer =
        isDatastar(request) ? stream(request, patches -> patches.redirect(path)) : Response.seeOther(path);
    return flashKey == null ? answer : setFlash(answer, flashKey);
  }

  /** The {@code Set-Cookie} value: {@code Secure} unless the application said plain HTTP is acceptable. */
  private String flashCookie(String value, int maxAge) {
    return FLASH_COOKIE + "=" + value + "; Path=/; Max-Age=" + maxAge + "; HttpOnly; SameSite=Lax" + (plainCookies ? "" : "; Secure");
  }

  /**
   * A Datastar answer from a callback that writes patches.
   *
   * @param request the request
   * @param events the callback
   * @return the streamed response
   */
  public Response stream(Request request, Events events) {
    Messages messages = messages(request);
    return Datastar.stream(
        request,
        sse ->
            Messages.where(
                messages,
                () -> {
                  events.run(new Patches(sse, FLASH_STACK_ID));
                  return null;
                }));
  }

  /** Writes the patches of one answer. */
  @FunctionalInterface
  public interface Events {
    /**
     * Writes patches.
     *
     * @param patches the answer
     * @throws Exception any failure; it is logged and ends the stream
     */
    void run(Patches patches) throws Exception;
  }

  private static Content flashStack(List<Props.FlashMessage> flashes) {
    Content messages =
        out -> {
          for (Props.FlashMessage m : flashes) {
            Kit.flashMessage(m).writeTo(out);
          }
        };
    return Kit.flashStack(new Props.FlashStack(FLASH_STACK_ID), messages);
  }

  static Content wrap(Content content) {
    return output -> {
      output.writeContent("<div id=\"" + PAGE_ID + "\">");
      content.writeTo(output);
      output.writeContent("</div>");
    };
  }

  private Response document(
      Request request,
      int status,
      String title,
      Content wrapped,
      List<Props.FlashMessage> pageFlashes,
      List<String> scripts) {
    Map<String, String> cookies = cookies(request.header("Cookie"));
    // the message the flash cookie's key names (none for an unknown or malformed key); the cookie is cleared either way
    String flashKey = cookies.get(FLASH_COOKIE);
    List<Props.FlashMessage> flashes = new ArrayList<>();
    Messages messages = messages(request);
    if (flashKey != null && FLASH_KEY.matcher(flashKey).matches() && this.flashes.containsKey(flashKey)) {
      flashes.add(localize(messages, flashKey, this.flashes.get(flashKey)));
    }
    flashes.addAll(pageFlashes);
    String theme = Theme.normalize(cookies.get(Theme.THEME_COOKIE));
    boolean dark =
        theme.equals("dark") || (theme.equals("system") && "dark".equals(cookies.get(Theme.SCHEME_COOKIE)));
    Map<String, Object> html = new LinkedHashMap<>();
    Content body;
    if (shell != null) {
      html.putAll(Theme.shellHtmlAttrs("collapsed".equals(cookies.get(Theme.SIDEBAR_COOKIE))));
      Props.AppShell s = shell.apply(request);
      Content inside =
          out -> {
            flashStack(flashes).writeTo(out);
            wrapped.writeTo(out);
          };
      Props.AppShell withTheme =
          new Props.AppShell(
              s.logoHref(),
              s.nav(),
              s.user(),
              s.version(),
              s.profileHref(),
              s.resetPasswordHref(),
              s.signOutAction(),
              s.signOutAttrs(),
              s.theme() != null ? s.theme() : theme);
      body = Kit.appShell(withTheme, inside);
    } else {
      body =
          out -> {
            out.writeContent("<main class=\"mx-auto max-w-xl p-[24px]\">");
            flashStack(flashes).writeTo(out);
            wrapped.writeTo(out);
            out.writeContent("</main>");
          };
    }
    html.putAll(Theme.htmlAttrs(theme, dark));
    Props.Document doc =
        new Props.Document(
            title + titleSuffix,
            html,
            assets.url("kit.css"),
            assets.url("datastar.js"),
            loginPath,
            loginParam,
            scripts,
            messages.locale());
    String markup = render(messages, Kit.document(doc, body));
    Response answer = Response.html(status, markup)
        .header("Content-Security-Policy", csp)
        .header("Cross-Origin-Opener-Policy", "same-origin")
        .header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "private, no-cache");
    return flashKey == null ? answer : answer.addHeader("Set-Cookie", flashCookie("", 0));
  }

  /** The registered message with its summary from {@code flash.<key>} and its detail from {@code flash.<key>.detail} when the catalogs have them. */
  private static Props.FlashMessage localize(Messages messages, String key, Props.FlashMessage m) {
    String summary = messages.has("flash." + key) ? messages.t("flash." + key) : m.summary();
    String detail = messages.has("flash." + key + ".detail") ? messages.t("flash." + key + ".detail") : m.detail();
    return new Props.FlashMessage(m.kind(), summary, detail, m.persistent(), m.attrs());
  }

  private static String render(Messages messages, Content content) {
    try {
      return Messages.where(messages, () -> Markup.render(content));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("rendering failed", e);
    }
  }

  private static Map<String, String> cookies(@Nullable String header) {
    Map<String, String> out = new LinkedHashMap<>();
    if (header == null) {
      return out;
    }
    for (String part : header.split(";")) {
      int eq = part.indexOf('=');
      if (eq > 0) {
        out.putIfAbsent(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
      }
    }
    return out;
  }
}
