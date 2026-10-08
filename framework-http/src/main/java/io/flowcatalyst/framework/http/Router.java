package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.pipeline.Pipeline;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A trie of path segments. Literals beat parameters at the same position. */
final class Router {

  /** A route ready to run. */
  record Compiled(
      Method method,
      RoutePattern pattern,
      String registrant,
      boolean requiresContext,
      Pipeline<Request, Response> pipeline) {}

  sealed interface Match {}

  record Found(Compiled route, Map<String, String> params) implements Match {}

  record MethodNotAllowed(Set<Method> allowed) implements Match {}

  record Options(Set<Method> allowed) implements Match {}

  record NotFound() implements Match {}

  private static final class Node {
    final Map<String, Node> literals = new HashMap<>();
    @Nullable Node param;
    final Map<Method, Compiled> routes = new java.util.EnumMap<>(Method.class);
  }

  private record Candidate(Node node, List<String> values) {}

  private final Node root = new Node();

  void add(Compiled route) {
    Node n = root;
    RoutePattern p = route.pattern();
    for (int i = 0; i < p.segments().size(); i++) {
      if (p.isParam(i)) {
        Node next = n.param;
        if (next == null) {
          next = new Node();
          n.param = next;
        }
        n = next;
      } else {
        n = n.literals.computeIfAbsent(p.segments().get(i), k -> new Node());
      }
    }
    n.routes.put(route.method(), route);
  }

  /**
   * Matches decoded segments. Among every route whose shape fits, the one that is most literal at
   * the first differing position wins; if none has the method the result lists the methods that
   * the path does have.
   */
  Match match(Method method, List<String> segments) {
    List<Candidate> candidates = new ArrayList<>();
    collect(root, segments, 0, new ArrayList<>(), candidates);
    if (candidates.isEmpty()) {
      return new NotFound();
    }
    for (Candidate c : candidates) {
      Compiled route = c.node().routes.get(method);
      if (route == null && method == Method.HEAD) {
        route = c.node().routes.get(Method.GET);
      }
      if (route != null) {
        Map<String, String> params = new HashMap<>();
        List<String> names = route.pattern().paramNames();
        for (int i = 0; i < names.size(); i++) {
          params.put(names.get(i), c.values().get(i));
        }
        return new Found(route, params);
      }
    }
    Set<Method> allowed = EnumSet.of(Method.OPTIONS);
    for (Candidate c : candidates) {
      allowed.addAll(c.node().routes.keySet());
    }
    if (allowed.contains(Method.GET)) {
      allowed.add(Method.HEAD);
    }
    return method == Method.OPTIONS ? new Options(allowed) : new MethodNotAllowed(allowed);
  }

  private void collect(Node n, List<String> segs, int i, List<String> values, List<Candidate> out) {
    if (i == segs.size()) {
      if (!n.routes.isEmpty()) {
        out.add(new Candidate(n, List.copyOf(values)));
      }
      return;
    }
    String seg = segs.get(i);
    Node lit = n.literals.get(seg);
    if (lit != null) {
      collect(lit, segs, i + 1, values, out);
    }
    Node param = n.param;
    if (param != null && !seg.isEmpty()) {
      values.add(seg);
      collect(param, segs, i + 1, values, out);
      values.removeLast();
    }
  }
}
