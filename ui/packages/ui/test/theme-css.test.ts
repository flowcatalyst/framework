// The theme (src/theme/theme.css) sets Nuxt UI's tokens from the ui-kit's (ui-kit/theme/tokens.css,
// the source of the values). This test fails when a value differs from the kit's other than by the
// listed shade changes (owner ruling 2026-10-14: the failing pairs one shade darker, same hue), and it
// pins the contrast those changes buy (WCAG 2.2 AA, 4.5:1 for text).
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const read = (relative: string): string => readFileSync(join(import.meta.dirname, relative), "utf8");
const tokens = read("../../../../ui-kit/theme/tokens.css");
const theme = read("../src/theme/theme.css");

/** Custom properties of the first block whose selector line is exactly `selector {`. */
function block(css: string, selector: string): Map<string, string> {
  const start = css.indexOf(`\n${selector} {`);
  if (start === -1) {
    throw new Error(`no "${selector} {" block`);
  }
  const end = css.indexOf("\n}", start);
  const body = css.slice(start, end).replace(/\/\*[\s\S]*?\*\//g, "");
  const result = new Map<string, string>();
  for (const match of body.matchAll(/(--[\w-]+):\s*([^;]+);/g)) {
    const [, name, value] = match;
    if (name !== undefined && value !== undefined) {
      result.set(name, value.trim());
    }
  }
  return result;
}

/** Which kit token each Nuxt UI token takes its value from. */
const FROM_KIT: Readonly<Record<string, string>> = {
  "--ui-primary": "--primary",
  "--ui-success": "--success",
  "--ui-info": "--info",
  "--ui-warning": "--warning",
  "--ui-error": "--destructive",
  "--ui-text": "--foreground",
  "--ui-text-muted": "--muted-foreground",
  "--ui-text-toned": "--subtle-foreground",
  "--ui-bg": "--card",
  "--ui-bg-muted": "--background",
  "--ui-border": "--border",
  "--ui-border-accented": "--input",
};

/** The kit's Tailwind (v3) shades around the changed values: the change must be to the next shade. */
const PALETTE: Readonly<Record<string, readonly (readonly [number, string])[]>> = {
  emerald: [[500, "#10b981"], [600, "#059669"], [700, "#047857"]],
  green: [[500, "#22c55e"], [600, "#16a34a"], [700, "#15803d"]],
  sky: [[500, "#0ea5e9"], [600, "#0284c7"], [700, "#0369a1"]],
  orange: [[500, "#f97316"], [600, "#ea580c"], [700, "#c2410c"]],
  red: [[400, "#f87171"], [500, "#ef4444"], [600, "#dc2626"], [700, "#b91c1c"]],
};

/** The shade changes of the owner ruling: theme -> [kit value, theme value, hue]. */
const CHANGED: Readonly<Record<"light" | "dark", Readonly<Record<string, readonly [string, string, string]>>>> = {
  light: {
    "--ui-primary": ["#059669", "#047857", "emerald"],
    "--ui-success": ["#16a34a", "#15803d", "green"],
    "--ui-info": ["#0284c7", "#0369a1", "sky"],
    "--ui-warning": ["#ea580c", "#c2410c", "orange"],
    "--ui-error": ["#dc2626", "#b91c1c", "red"],
  },
  dark: {
    "--ui-error": ["#ef4444", "#f87171", "red"],
  },
};

function channel(value: number): number {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

type Rgb = readonly [number, number, number];

function rgb(hex: string): Rgb {
  return [Number.parseInt(hex.slice(1, 3), 16), Number.parseInt(hex.slice(3, 5), 16), Number.parseInt(hex.slice(5, 7), 16)];
}

function luminance(color: Rgb): number {
  return 0.2126 * channel(color[0]) + 0.7152 * channel(color[1]) + 0.0722 * channel(color[2]);
}

/** `fg` at `alpha` over `bg`, rounded as a browser does. */
function over(fg: string, alpha: number, bg: string): Rgb {
  const [fr, fg2, fb] = rgb(fg);
  const [br, bg2, bb] = rgb(bg);
  const mix = (f: number, b: number): number => Math.round(f * alpha + b * (1 - alpha));
  return [mix(fr, br), mix(fg2, bg2), mix(fb, bb)];
}

function contrast(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return ((hi ?? 0) + 0.05) / ((lo ?? 0) + 0.05);
}

const round = (value: number): number => Math.round(value * 100) / 100;

describe("theme.css starts from ui-kit/theme/tokens.css", () => {
  for (const [scheme, selector] of [["light", ":root"], ["dark", ".dark"]] as const) {
    it(`${scheme}: every Nuxt UI token is the kit's value, or the listed one-shade change`, () => {
      const kit = block(tokens, selector);
      const ours = block(theme, selector);
      for (const [ui, kitName] of Object.entries(FROM_KIT)) {
        const value = ours.get(ui);
        expect(value, `${scheme} ${ui}`).toBeDefined();
        const changed = CHANGED[scheme][ui];
        if (changed === undefined) {
          expect(value, `${scheme} ${ui} = kit ${kitName}`).toBe(kit.get(kitName));
          continue;
        }
        const [from, to, hue] = changed;
        expect(kit.get(kitName), `${scheme} kit ${kitName}`).toBe(from);
        expect(value, `${scheme} ${ui}`).toBe(to);
        const shades = PALETTE[hue] ?? [];
        const fromShade = shades.find(([, hex]) => hex === from)?.[0];
        const toShade = shades.find(([, hex]) => hex === to)?.[0];
        expect(Math.abs((fromShade ?? 0) - (toShade ?? 0)), `${ui}: one shade of ${hue}`).toBe(100);
      }
    });
  }

  it("the navy and accent palette is the kit's", () => {
    const kit = block(tokens, "@theme inline");
    const ours = [...block(theme, "@theme")].filter(([name]) => name.startsWith("--color-"));
    expect(ours.length).toBe(14);
    for (const [name, value] of ours) {
      expect(value, name).toBe(kit.get(name));
    }
  });
});

describe("contrast of the colour aliases (WCAG AA, 4.5:1)", () => {
  const aliases = ["--ui-primary", "--ui-success", "--ui-info", "--ui-warning", "--ui-error"] as const;
  const light = block(theme, ":root");
  const dark = block(theme, ".dark");
  const color = (map: Map<string, string>, name: string): string => map.get(name) ?? "#000000";

  it("light: white on the fill (solid buttons and badges) and the colour as text on white (links, outline)", () => {
    // Text of the colour at 90 % opacity (Nuxt UI's alert description) is not used: fcComponentTheme
    // draws alert descriptions at full opacity (success and warning would read 4.17 and 4.42).
    for (const alias of aliases) {
      const fill = color(light, alias);
      expect(round(contrast(rgb("#ffffff"), rgb(fill))), alias).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("dark: the colour as text on the card (--ui-bg) and on its 10 % tint", () => {
    const card = color(dark, "--ui-bg");
    for (const alias of aliases) {
      const fg = color(dark, alias);
      expect(round(contrast(rgb(fg), rgb(card))), alias).toBeGreaterThanOrEqual(4.5);
      expect(round(contrast(rgb(fg), over(fg, 0.1, card))), `${alias} on its tint`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("light, text on the colour's own 10 % tint (soft and subtle variants): success and warning stay below AA", () => {
    // One shade is not enough for these two (an owner decision, docs/ui/u1.md): no FlowCatalyst part
    // uses a soft or subtle success or warning, and the gallery does not show one. The ratios are
    // pinned so a change of the tokens shows here.
    const ratios = Object.fromEntries(aliases.map((alias) => {
      const fg = color(light, alias);
      return [alias, round(contrast(rgb(fg), over(fg, 0.1, "#ffffff")))];
    }));
    expect(ratios).toEqual({ "--ui-primary": 4.78, "--ui-success": 4.38, "--ui-info": 5.13, "--ui-warning": 4.48, "--ui-error": 5.45 });
  });
});
