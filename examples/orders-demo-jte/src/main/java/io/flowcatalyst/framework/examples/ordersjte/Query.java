package io.flowcatalyst.framework.examples.ordersjte;

import io.flowcatalyst.framework.http.Request;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The list's state, read from the query string and written back into every link: search text,
 * status filter, sort, page and page size. Unknown values fall back to the defaults, so a link
 * from anywhere renders.
 *
 * @param q the search text, or null
 * @param status the status filter, or null
 * @param sort customer, status, total or created
 * @param desc whether the sort is descending
 * @param page the page, 1-based
 * @param rows rows per page
 */
public record Query(@Nullable String q, @Nullable String status, String sort, boolean desc, int page, int rows) {

  /** The page sizes the pager offers. */
  public static final List<Integer> ROWS = List.of(5, 10, 25);

  private static final List<String> SORTS = List.of("customer", "status", "total", "created");

  /**
   * Reads the list state of a request.
   *
   * @param r the request
   * @return the state, defaults for anything missing or unknown
   */
  public static Query of(Request r) {
    String q = r.query("q");
    String status = r.query("status");
    String sort = r.query("sort");
    String dir = r.query("dir");
    int rows = number(r.query("rows"), 10);
    return new Query(
        q == null || q.isBlank() ? null : q.strip(),
        status != null && Order.STATUSES.contains(status) ? status : null,
        sort != null && SORTS.contains(sort) ? sort : "created",
        !"asc".equals(dir),
        number(r.query("page"), 1),
        ROWS.contains(rows) ? rows : 10);
  }

  private static int number(@Nullable String s, int fallback) {
    if (s == null || !s.matches("\\d{1,6}")) {
      return fallback;
    }
    return Integer.parseInt(s);
  }

  /**
   * Whether a search or filter is active.
   *
   * @return true when "Clear All" has something to clear
   */
  public boolean filtered() {
    return q != null || status != null;
  }

  /**
   * The number of active filters (the toolbar's badge).
   *
   * @return 1 with a status filter, else 0
   */
  public int activeFilters() {
    return status == null ? 0 : 1;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private String base(String sortBy, boolean descending) {
    StringBuilder b = new StringBuilder("/orders?sort=").append(sortBy).append("&dir=").append(descending ? "desc" : "asc");
    if (q != null) {
      b.append("&q=").append(enc(q));
    }
    if (status != null) {
      b.append("&status=").append(status);
    }
    b.append("&rows=").append(rows);
    return b.toString();
  }

  /**
   * The link that sorts by a column: the next direction when it is the sort already.
   *
   * @param column customer, status, total or created
   * @return the list URL
   */
  public String sortHref(String column) {
    boolean nextDesc = sort.equals(column) ? !desc : column.equals("created");
    return base(column, nextDesc);
  }

  /**
   * The direction to show on a column heading.
   *
   * @param column the column
   * @return asc or desc for the sorted column, null otherwise
   */
  public @Nullable String direction(String column) {
    return sort.equals(column) ? (desc ? "desc" : "asc") : null;
  }

  /**
   * A page link pattern for the pager, with the literal placeholder {@code {page}}.
   *
   * @return the pattern
   */
  public String pagePattern() {
    return base(sort, desc) + "&page={page}";
  }
}
