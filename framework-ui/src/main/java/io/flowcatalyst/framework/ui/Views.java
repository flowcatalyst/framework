package io.flowcatalyst.framework.ui;

import gg.jte.Content;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.datastar.Datastar;
import io.flowcatalyst.framework.ui.kit.Kit;
import io.flowcatalyst.framework.ui.kit.Markup;
import io.flowcatalyst.framework.ui.kit.Props;
import io.flowcatalyst.framework.ui.kit.Theme;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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

  private final UiAssets assets;
  private final String titleSuffix;
  private final @Nullable Function<Request, Props.AppShell> shell;
  private final @Nullable String loginPath;
  private final String csp;

  private Views(Builder b) {
    this.assets = b.assets;
    this.titleSuffix = b.titleSuffix;
    this.shell = b.shell;
    this.loginPath = b.loginPath;
    this.csp = ViewCsp.policy(b.scriptHashes.toArray(String[]::new));
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
    private final List<String> scriptHashes = new ArrayList<>();

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
     * {@code ?redirect=} the current page.
     *
     * @param path for example {@code /auth/login}
     * @return this
     */
    public Builder sessionExpiredLogin(String path) {
      this.loginPath = Markup.path(path);
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
     * Builds the helper.
     *
     * @return the views
     */
    public Views build() {
      return new Views(this);
    }
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
    Content wrapped = wrap(content);
    if (isDatastar(request)) {
      return stream(request, patches -> patches.outer(wrapped));
    }
    return document(request, status, title, wrapped, flashes);
  }

  /**
   * A Datastar answer from a callback that writes patches.
   *
   * @param request the request
   * @param events the callback
   * @return the streamed response
   */
  public Response stream(Request request, Events events) {
    return Datastar.stream(request, sse -> events.run(new Patches(sse, FLASH_STACK_ID)));
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
      Request request, int status, String title, Content wrapped, List<Props.FlashMessage> flashes) {
    Map<String, String> cookies = cookies(request.header("Cookie"));
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
            title + titleSuffix, html, assets.url("kit.css"), assets.url("datastar.js"), loginPath);
    String markup = Markup.render(Kit.document(doc, body));
    return Response.html(status, markup)
        .header("Content-Security-Policy", csp)
        .header("Cross-Origin-Opener-Policy", "same-origin")
        .header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "private, no-cache");
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
