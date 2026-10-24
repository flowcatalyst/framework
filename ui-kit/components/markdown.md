# Markdown

**Source:** new (Go platform port; the documentation pages)  
**Mechanism:** (a) server-rendered, sanitised HTML  

## Purpose

Markdown from a file or from user/app data, rendered on the server to safe HTML inside a `.fc-markdown` wrapper. Templates must not render Markdown any other way.

## Structure

```html
<div class="fc-markdown"><h1 id="title">Title</h1>
<p>Some <em>emphasis</em>, <strong>bold</strong>, <code>code</code> and a <a href="https://example.com/docs">link</a>.</p>
<h2 id="table">Table</h2>
<table>
<thead>
<tr>
<th>Name</th>
<th style="text-align:center">Tier</th>
</tr>
</thead>
<tbody>
<tr>
<td>ada</td>
<td style="text-align:center">ANCHOR</td>
</tr>
</tbody>
</table>
<pre><code class="language-json">{&#34;a&#34;: 1}
</code></pre>
<ul>
<li>one</li>
<li>two</li>
</ul>
</div>
```

## Rendering and policy

- Parser: CommonMark plus the GFM extensions (tables, strikethrough, task lists, autolinks); headings get ids from their text (`## Second factors` is `id="second-factors"`) so a page can link to them.
- The output is passed through an allow-list sanitiser. Kept: the usual text, list, table, code, quote and image elements; `id` on headings; `language-*` classes on `code`; table-cell alignment; display-only task-list checkboxes. Links keep `http`, `https`, `mailto` and relative URLs.
- Removed (the element or attribute, not the text around it): `script`, `style`, `iframe`, `svg`, forms, `on*` attributes, `javascript:` and `data:` URLs, any other attribute or class.
- Raw HTML in the source is treated as untrusted: it goes through the same filter.
- A render failure shows the source in a `<pre>`.

## Props

Fixtures, from the ui-kit root: `fixtures/markdown`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `source` | string | - | Markdown text |
| `class` | string | - | appended to `fc-markdown` |

## Accessibility

Headings keep their levels; the document author owns the outline. Tables scroll horizontally inside the wrapper at narrow widths.

## Notes for a template author

Fenced code is plain `<pre><code class="language-x">`; a code highlighter (`code-block.md`) or an island can enhance it. Ids come from heading text and can collide with page ids: render one document per page region.
