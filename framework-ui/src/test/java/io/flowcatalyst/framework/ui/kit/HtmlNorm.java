package io.flowcatalyst.framework.ui.kit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.parser.Tag;

/**
 * The comparison behind the fixture conformance test: the rules of {@code ui-kit/fixtures/README.md}
 * (HTML5 fragment parse in the context of the element that would contain the first element,
 * comments dropped, attributes sorted with whitespace collapsed, class token order kept, text
 * collapsed and trimmed, one node per line indented by depth). The Go implementation is {@code
 * internal/ui/htmlnorm} in the platform.
 */
final class HtmlNorm {

  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final Pattern FIRST_TAG =
      Pattern.compile("^\\s*(?:<!--[\\s\\S]*?-->\\s*)*<([a-zA-Z][a-zA-Z0-9]*)");

  private HtmlNorm() {}

  static String contextFor(String tag) {
    return switch (tag.toLowerCase(Locale.ROOT)) {
      case "tr" -> "tbody";
      case "td", "th" -> "tr";
      case "thead", "tbody", "tfoot", "caption", "colgroup" -> "table";
      case "col" -> "colgroup";
      case "option", "optgroup" -> "select";
      default -> "body";
    };
  }

  static String normalize(String fragment) {
    Matcher m = FIRST_TAG.matcher(fragment);
    String tag = m.find() ? m.group(1) : "div";
    Element ctx = new Element(Tag.valueOf(contextFor(tag)), "");
    List<Node> nodes = Parser.parseFragment(fragment, ctx, "");
    StringBuilder out = new StringBuilder();
    for (Node n : new ArrayList<>(nodes)) {
      write(out, n, 0);
    }
    return out.toString();
  }

  private static String collapse(String s) {
    return SPACES.matcher(s).replaceAll(" ").strip();
  }

  private static void write(StringBuilder b, Node n, int depth) {
    String indent = "  ".repeat(depth);
    switch (n) {
      case Comment c -> {}
      case TextNode t -> {
        String s = collapse(t.getWholeText());
        if (!s.isEmpty()) {
          b.append(indent).append(s).append('\n');
        }
      }
      case DataNode d -> {
        String s = collapse(d.getWholeData());
        if (!s.isEmpty()) {
          b.append(indent).append(s).append('\n');
        }
      }
      case Element e -> {
        List<Attribute> attrs = new ArrayList<>(e.attributes().asList());
        attrs.sort(Comparator.comparing(Attribute::getKey));
        b.append(indent).append('<').append(e.tagName());
        for (Attribute a : attrs) {
          b.append(' ').append(a.getKey()).append("=\"").append(collapse(a.getValue())).append('"');
        }
        b.append(">\n");
        for (Node c : e.childNodes()) {
          write(b, c, depth + 1);
        }
      }
      default -> {
        for (Node c : n.childNodes()) {
          write(b, c, depth);
        }
      }
    }
  }
}
