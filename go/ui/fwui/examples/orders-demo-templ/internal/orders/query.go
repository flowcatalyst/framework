package orders

import (
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

// PageSizes are the page sizes the pager offers.
var PageSizes = []int{5, 10, 25}

var sorts = []string{"customer", "status", "total", "created"}

// Query is the list's state, read from the query string and written back into every link: search
// text, status filter, sort, page and page size. Unknown values fall back to the defaults, so a
// link from anywhere renders.
type Query struct {
	Q      string // the search text, "" for none
	Status string // the status filter, "" for none
	Sort   string // customer, status, total or created
	Desc   bool
	Page   int // 1-based
	Rows   int
}

// DefaultQuery is the list as it first opens: newest first, ten rows.
func DefaultQuery() Query { return Query{Sort: "created", Desc: true, Page: 1, Rows: 10} }

// QueryOf reads the list state of a request.
func QueryOf(r *http.Request) Query {
	v := r.URL.Query()
	q := DefaultQuery()
	q.Q = strings.TrimSpace(v.Get("q"))
	if s := v.Get("status"); slices.Contains(Statuses, s) {
		q.Status = s
	}
	if s := v.Get("sort"); slices.Contains(sorts, s) {
		q.Sort = s
	}
	q.Desc = v.Get("dir") != "asc"
	q.Page = number(v.Get("page"), 1)
	if n := number(v.Get("rows"), 10); slices.Contains(PageSizes, n) {
		q.Rows = n
	}
	return q
}

var digits = regexp.MustCompile(`^\d{1,6}$`)

func number(s string, fallback int) int {
	if !digits.MatchString(s) {
		return fallback
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return fallback
	}
	return n
}

// Filtered reports whether a search or filter is active (Clear All has something to clear).
func (q Query) Filtered() bool { return q.Q != "" || q.Status != "" }

// ActiveFilters is the number of active filters (the toolbar's badge): 1 with a status filter.
func (q Query) ActiveFilters() int {
	if q.Status == "" {
		return 0
	}
	return 1
}

func (q Query) base(sortBy string, descending bool) string {
	dir := "asc"
	if descending {
		dir = "desc"
	}
	var b strings.Builder
	b.WriteString("/orders?sort=" + sortBy + "&dir=" + dir)
	if q.Q != "" {
		b.WriteString("&q=" + url.QueryEscape(q.Q))
	}
	if q.Status != "" {
		b.WriteString("&status=" + q.Status)
	}
	b.WriteString("&rows=" + strconv.Itoa(q.Rows))
	return b.String()
}

// SortHref is the link that sorts by a column: the next direction when it is the sort already.
func (q Query) SortHref(column string) string {
	next := column == "created"
	if q.Sort == column {
		next = !q.Desc
	}
	return q.base(column, next)
}

// Direction is the direction to show on a column heading: asc or desc for the sorted column.
func (q Query) Direction(column string) string {
	switch {
	case q.Sort != column:
		return ""
	case q.Desc:
		return "desc"
	default:
		return "asc"
	}
}

// PagePattern is the page link pattern for the pager, with the literal placeholder {page}.
func (q Query) PagePattern() string { return q.base(q.Sort, q.Desc) + "&page={page}" }
