// <fc-diagram>: renders the Mermaid source in its <pre> as an SVG, in
// Mermaid's strict security level. The Mermaid bundle (data-bundle, a
// same-origin URL) is loaded once, on the first diagram, so pages without
// diagrams never fetch it. Without script the source stays readable.
//
// The drawing must never carry Datastar attributes: Datastar evaluates
// data-init / data-effect / data-signals / data-on... on any node added to the
// page, and a diagram label is author-controlled HTML that Mermaid's DOMPurify
// pass would otherwise keep data-* on. Three layers, each sufficient for the
// label case on its own:
//   1. Mermaid's label sanitizer runs with ALLOW_DATA_ATTR off and the
//      Datastar attribute names forbidden;
//   2. Mermaid renders into a scratch node inside the view, which the
//      template marks data-ignore (Datastar skips it and its descendants),
//      instead of into document.body;
//   3. the finished SVG is parsed into an inert <template>, stripped of every
//      Datastar attribute, and only then moved into the view.
let loading;

function loadMermaid(src) {
  if (globalThis.mermaid) return Promise.resolve(globalThis.mermaid);
  loading ??= new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = src;
    s.onload = () => resolve(globalThis.mermaid);
    s.onerror = () => reject(new Error("mermaid bundle did not load"));
    document.head.append(s);
  });
  return loading;
}

// Datastar 1.0.4's attribute plugins. Mermaid's own data-* (data-look,
// data-id, data-et, data-color-id...) are styling hooks and stay.
const DATASTAR_PLUGINS = [
  "attr", "bind", "class", "computed", "effect", "ignore", "ignore-morph", "indicator", "init",
  "json-signals", "on", "on-intersect", "on-interval", "on-signal-patch", "persist", "ref",
  "replace-url", "scroll-into-view", "show", "signals", "style", "text", "view-transition",
];
// data-<plugin> with an optional :key, __modifier or -suffix (data-on:click,
// data-signals:x, data-on-intersect__once).
const DATASTAR_ATTR = new RegExp("^data-(" + DATASTAR_PLUGINS.join("|") + ")(?![a-z0-9])", "i");

const DOMPURIFY_CONFIG = {
  FORBID_TAGS: ["style"], // Mermaid's own default for labels, kept
  ALLOW_DATA_ATTR: false,
  FORBID_ATTR: DATASTAR_PLUGINS.map((p) => "data-" + p),
};

function scrub(root) {
  for (const el of root.querySelectorAll("*")) {
    for (const { name } of [...el.attributes]) {
      if (DATASTAR_ATTR.test(name)) el.removeAttribute(name);
    }
  }
}

let seq = 0;

class FCDiagram extends HTMLElement {
  connectedCallback() {
    const view = this.querySelector(".fc-diagram-view");
    const source = this.querySelector("pre")?.textContent ?? "";
    if (!view || !source.trim() || view.dataset.drawn) return;
    view.dataset.drawn = "";
    view.textContent = "Rendering diagram...";
    view.hidden = false;
    // Mermaid measures text while it draws, so the scratch node is laid out
    // (off-screen, invisible) rather than display:none.
    const scratch = document.createElement("div");
    scratch.style.position = "absolute";
    scratch.style.left = "-10000px";
    scratch.style.top = "0";
    scratch.style.visibility = "hidden";
    view.append(scratch);
    loadMermaid(this.dataset.bundle)
      .then(async (mermaid) => {
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: "strict",
          suppressErrorRendering: true,
          dompurifyConfig: DOMPURIFY_CONFIG,
          theme: document.documentElement.classList.contains("dark") ? "dark" : "default",
        });
        const { svg } = await mermaid.render("fc-diagram-" + ++seq, source, scratch);
        const t = document.createElement("template");
        t.innerHTML = svg;
        scrub(t.content);
        view.replaceChildren(t.content);
      })
      .catch(() => {
        view.replaceChildren(); // also drops Mermaid's scratch nodes after a failed render
        view.hidden = true;
        this.querySelector("details")?.setAttribute("open", "");
        this.dataset.failed = "";
      });
  }
}

if (!customElements.get("fc-diagram")) customElements.define("fc-diagram", FCDiagram);
