// <fc-code-editor>: a CodeMirror 6 editor that behaves like a form control.
//
//   <fc-code-editor language="json" readonly>
//     <textarea name="payload" aria-label="Payload" data-bind:payload>{...}</textarea>
//   </fc-code-editor>
//
// The server renders the textarea (the control without script, and the
// initial text). Element attributes: language (json or yaml; otherwise plain
// text) and readonly. The textarea's name, aria-label and data-bind*
// attributes move to the hidden input and the editor, so Datastar binds a
// signal to the text and a form posts it under the same name.
//
// The text is mirrored into a hidden <input> inside the element on every
// change (an "input" event is dispatched on it), so an ordinary form post,
// a form reset, a dirty-form check and a Datastar signal all see the value
// like any other field. The editor is not updated from the input afterwards
// (a signal written elsewhere does not reach it).
import { Compartment, EditorState } from "@codemirror/state";
import {
  EditorView,
  drawSelection,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers,
} from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import {
  HighlightStyle,
  bracketMatching,
  foldGutter,
  foldKeymap,
  indentOnInput,
  syntaxHighlighting,
} from "@codemirror/language";
import { json } from "@codemirror/lang-json";
import { yaml } from "@codemirror/lang-yaml";
import { tags as t } from "@lezer/highlight";

const languages = { json, yaml };

// Two palettes, picked when the editor is created from the page's theme
// (the kit's <html class="dark">).
const palette = (key, str, num, kw, mute) =>
  HighlightStyle.define([
    { tag: [t.propertyName, t.definition(t.propertyName)], color: key },
    { tag: [t.string, t.special(t.string)], color: str },
    { tag: [t.number, t.bool, t.null], color: num },
    { tag: [t.keyword, t.atom], color: kw },
    { tag: [t.comment], color: mute, fontStyle: "italic" },
    { tag: [t.punctuation, t.separator, t.bracket], color: mute },
  ]);
const light = palette("#0369a1", "#15803d", "#b45309", "#7c3aed", "#64748b");
const dark = palette("#7dd3fc", "#86efac", "#fcd34d", "#c4b5fd", "#94a3b8");

const theme = EditorView.theme({
  "&": {
    fontSize: "13px",
    color: "var(--foreground)",
    backgroundColor: "var(--card, transparent)",
    border: "1px solid var(--border)",
    borderRadius: "6px",
  },
  "&.cm-focused": { outline: "2px solid var(--primary)", outlineOffset: "-1px" },
  ".cm-scroller": { fontFamily: "var(--font-mono, monospace)", lineHeight: "1.6", minHeight: "8rem", maxHeight: "28rem" },
  ".cm-gutters": { backgroundColor: "var(--subtle, transparent)", color: "var(--muted-foreground)", border: "none" },
  ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: "color-mix(in srgb, var(--primary) 7%, transparent)" },
  ".cm-cursor": { borderLeftColor: "var(--foreground)" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground": {
    backgroundColor: "color-mix(in srgb, var(--primary) 25%, transparent)",
  },
  ".cm-matchingBracket": { outline: "1px solid var(--primary)", backgroundColor: "transparent" },
});

class FCCodeEditor extends HTMLElement {
  #view;
  #input;
  #initial = "";
  #readonly = new Compartment();

  connectedCallback() {
    if (this.#view) return;
    const area = this.querySelector("textarea");
    if (!area) return;
    const input = (this.#input = document.createElement("input"));
    input.type = "hidden";
    input.name = area.name;
    input.value = area.value;
    for (const a of area.attributes) {
      if (a.name.startsWith("data-bind")) input.setAttribute(a.name, a.value);
    }
    const host = document.createElement("div");
    area.replaceWith(input, host);
    this.#initial = area.defaultValue;
    const label = area.getAttribute("aria-label");

    const readonly = this.hasAttribute("readonly");
    const language = languages[this.getAttribute("language")];
    this.#view = new EditorView({
      parent: host,
      state: EditorState.create({
        doc: input.value,
        extensions: [
          lineNumbers(),
          foldGutter(),
          drawSelection(),
          highlightActiveLine(),
          highlightActiveLineGutter(),
          bracketMatching(),
          indentOnInput(),
          history(),
          syntaxHighlighting(document.documentElement.classList.contains("dark") ? dark : light),
          theme,
          keymap.of([...defaultKeymap, ...historyKeymap, ...foldKeymap, indentWithTab]),
          language ? language() : [],
          this.#readonly.of(this.#readonlyExtensions(readonly)),
          EditorView.contentAttributes.of({ "aria-label": label ?? "Code editor" }),
          EditorView.updateListener.of((u) => {
            if (!u.docChanged) return;
            input.value = u.state.doc.toString();
            input.dispatchEvent(new Event("input", { bubbles: true }));
          }),
        ],
      }),
    });
    this.closest("form")?.addEventListener("reset", this.#reset);
  }

  static observedAttributes = ["readonly"];

  attributeChangedCallback(name) {
    if (name === "readonly" && this.#view) {
      this.#view.dispatch({ effects: this.#readonly.reconfigure(this.#readonlyExtensions(this.hasAttribute("readonly"))) });
    }
  }

  #readonlyExtensions(on) {
    return on ? [EditorState.readOnly.of(true), EditorView.editable.of(false)] : [];
  }

  // A form reset puts the initial text back (after the input has been reset
  // to its default, which for a hidden input is the value attribute).
  #reset = () => {
    this.#view?.dispatch({ changes: { from: 0, to: this.#view.state.doc.length, insert: this.#initial } });
  };
}

if (!customElements.get("fc-code-editor")) customElements.define("fc-code-editor", FCCodeEditor);
