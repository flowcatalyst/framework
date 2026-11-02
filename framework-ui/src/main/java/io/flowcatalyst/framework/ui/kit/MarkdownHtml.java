package io.flowcatalyst.framework.ui.kit;

import java.util.List;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Markdown to sanitised HTML (contract server-rendered-ui.md section 11; the Go module's {@code md}
 * package is the counterpart). commonmark-java parses CommonMark plus the GFM tables, strikethrough,
 * task lists and autolinks, with heading ids; raw HTML in the source is passed on and then removed by
 * the OWASP sanitiser, whose policy keeps the usual text, list, table, code, quote and image
 * elements, heading ids, {@code language-*} classes on {@code code}, table-cell alignment,
 * display-only task-list checkboxes and links with {@code http}, {@code https}, {@code mailto} or a
 * relative URL.
 */
public final class MarkdownHtml {

  private MarkdownHtml() {}

  private static final List<Extension> EXTENSIONS =
      List.of(
          TablesExtension.create(),
          StrikethroughExtension.create(),
          TaskListItemsExtension.create(),
          AutolinkExtension.create(),
          HeadingAnchorExtension.create());

  private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

  /** Writes a table cell's alignment as the {@code text-align} style the policy keeps. */
  private static final AttributeProvider ALIGNMENT =
      (node, tagName, attributes) -> {
        if (node instanceof TableCell cell && cell.getAlignment() != null) {
          attributes.remove("align");
          attributes.put("style", "text-align:" + cell.getAlignment().name().toLowerCase(java.util.Locale.ROOT));
        }
      };

  private static final HtmlRenderer RENDERER =
      HtmlRenderer.builder()
          .extensions(EXTENSIONS)
          .attributeProviderFactory(context -> ALIGNMENT)
          .escapeHtml(false)
          .build();

  private static final PolicyFactory POLICY =
      new HtmlPolicyBuilder()
          .allowElements(
              "h1", "h2", "h3", "h4", "h5", "h6", "p", "br", "hr", "blockquote", "pre", "code", "em",
              "strong", "b", "i", "u", "s", "del", "ins", "sub", "sup", "kbd", "ul", "ol", "li", "dl",
              "dt", "dd", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "div", "span")
          .allowElements("a")
          .allowElements("img")
          .allowElements("input")
          .allowUrlProtocols("http", "https", "mailto")
          .allowAttributes("href", "title").onElements("a")
          .allowAttributes("src", "alt", "title", "width", "height").onElements("img")
          .allowAttributes("id").matching(Pattern.compile("[A-Za-z][A-Za-z0-9_-]*")).onElements("h1", "h2", "h3", "h4", "h5", "h6")
          .allowAttributes("class").matching(Pattern.compile("language-[A-Za-z0-9_+-]+")).onElements("code")
          .allowAttributes("style").matching(Pattern.compile("text-align:\\s?(left|right|center)")).onElements("th", "td")
          .allowAttributes("type").matching(Pattern.compile("checkbox")).onElements("input")
          .allowAttributes("checked", "disabled").onElements("input")
          .toFactory();

  /**
   * Renders Markdown source to sanitised HTML.
   *
   * @param source the Markdown; it may come from user data
   * @return HTML safe to embed (no script, no event handler, no unlisted attribute or URL scheme)
   */
  public static String html(String source) {
    Node doc = PARSER.parse(source);
    return POLICY.sanitize(RENDERER.render(doc));
  }
}
