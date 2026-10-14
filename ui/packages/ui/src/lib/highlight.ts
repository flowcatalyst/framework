// A small syntax highlighter for FwCodeBlock (ui-kit code-block.md): it splits source text into
// tokens of the kit's kinds (key, str, num, kw, type, var, comment) and plain text. The component
// renders each token as a text node inside a span, so nothing is ever parsed as HTML (no v-html).
// It is a tokenizer, not a parser: good enough for JSON, JSON Schema and the snippet languages the
// framework shows (TypeScript, JavaScript, Java, Go, SQL, shell), never used for anything a user
// must rely on.

export type TokenKind = "key" | "str" | "num" | "kw" | "type" | "var" | "comment" | "plain";

export interface Token {
  readonly kind: TokenKind;
  readonly text: string;
}

export type CodeLanguage = "json" | "typescript" | "javascript" | "java" | "go" | "sql" | "shell" | "text";

const KEYWORDS: Readonly<Record<Exclude<CodeLanguage, "json" | "text">, ReadonlySet<string>>> = {
  typescript: new Set(
    "as async await break case catch class const continue default delete do else enum export extends false finally for from function if import in instanceof interface let new null of private protected public readonly return satisfies static super switch this throw true try type typeof undefined var void while yield".split(" "),
  ),
  javascript: new Set(
    "async await break case catch class const continue default delete do else export extends false finally for from function if import in instanceof let new null of return static super switch this throw true try typeof undefined var void while yield".split(" "),
  ),
  java: new Set(
    "abstract boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long new null package permits private protected public record return sealed short static super switch this throw throws true false try var void volatile while yield".split(" "),
  ),
  go: new Set(
    "break case chan const continue default defer else fallthrough false for func go goto if import interface map nil package range return select struct switch true type var".split(" "),
  ),
  sql: new Set(
    "select from where and or not null is in insert into values update set delete create table index primary key references on join left right inner outer group by order asc desc limit offset as returning with distinct having union all case when then else end begin commit rollback".split(" "),
  ),
  shell: new Set("if then else elif fi for in do done while case esac function export local return set unset".split(" ")),
};

interface Rule {
  readonly kind: TokenKind;
  readonly pattern: RegExp;
}

// Each pattern is sticky (`y`) and tried at the current position, in order.
const JSON_RULES: readonly Rule[] = [
  { kind: "key", pattern: /"(?:[^"\\\n]|\\.)*"(?=\s*:)/y },
  { kind: "str", pattern: /"(?:[^"\\\n]|\\.)*"/y },
  { kind: "num", pattern: /-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/y },
  { kind: "kw", pattern: /\b(?:true|false|null)\b/y },
];

function codeRules(language: Exclude<CodeLanguage, "json" | "text">): readonly Rule[] {
  const comment =
    language === "sql"
      ? /--[^\n]*/y
      : language === "shell"
        ? /#[^\n]*/y
        : /\/\/[^\n]*|\/\*[\s\S]*?(?:\*\/|$)/y;
  const strings = language === "go" || language === "typescript" || language === "javascript"
    ? /"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`/y
    : /"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'/y;
  return [
    { kind: "comment", pattern: comment },
    { kind: "str", pattern: strings },
    { kind: "var", pattern: language === "shell" ? /\$\{?[A-Za-z_][A-Za-z0-9_]*\}?/y : /@[A-Za-z_][A-Za-z0-9_.]*/y },
    { kind: "num", pattern: /\b\d+(?:\.\d+)?[lLfFdD]?\b/y },
    { kind: "plain", pattern: /[A-Za-z_$][A-Za-z0-9_$]*/y },
  ];
}

function classify(word: string, language: Exclude<CodeLanguage, "json" | "text">): TokenKind {
  const keywords = KEYWORDS[language];
  if (keywords.has(language === "sql" ? word.toLowerCase() : word)) {
    return "kw";
  }
  // A capitalised identifier reads as a type in every language listed here.
  return /^[A-Z][A-Za-z0-9_]*$/.test(word) && language !== "sql" ? "type" : "plain";
}

/** Splits `source` into tokens; concatenating their texts gives `source` back exactly. */
export function tokenize(source: string, language: CodeLanguage): Token[] {
  if (language === "text") {
    return source === "" ? [] : [{ kind: "plain", text: source }];
  }
  const rules = language === "json" ? JSON_RULES : codeRules(language);
  const tokens: Token[] = [];
  let plain = "";
  let index = 0;
  const flush = (): void => {
    if (plain !== "") {
      tokens.push({ kind: "plain", text: plain });
      plain = "";
    }
  };
  outer: while (index < source.length) {
    for (const rule of rules) {
      rule.pattern.lastIndex = index;
      const match = rule.pattern.exec(source);
      if (match !== null && match[0] !== "") {
        const text = match[0];
        const kind = language !== "json" && rule.kind === "plain" ? classify(text, language) : rule.kind;
        if (kind === "plain") {
          plain += text;
        } else {
          flush();
          tokens.push({ kind, text });
        }
        index += text.length;
        continue outer;
      }
    }
    plain += source.charAt(index);
    index += 1;
  }
  flush();
  return tokens;
}
