// Action feedback (ui-kit flash-stack.md) on Nuxt UI's toasts: success and info leave by themselves
// (3 s and 5 s, the SPA's), warnings and errors stay until dismissed (WCAG 2.2.1: text that must be
// read does not time out). Each message is also announced through FwAnnouncer's live regions: the
// toaster's own announcer is hidden from assistive technology in Reka UI 2.10.5 (useAnnouncer.ts).
// Look difference from the kit: toasts in a corner, not a banner stack at the top of the content.
import { useToast } from "@nuxt/ui/composables";
import { announce } from "./useAnnouncer.ts";

export type FlashKind = "success" | "info" | "warn" | "error";

export interface FlashOptions {
  readonly detail?: string;
  /** success/info: no auto-dismiss. */
  readonly persistent?: boolean;
  /** At most one message with this id at a time (a repeat replaces it). */
  readonly id?: string;
}

export type Flash = Readonly<Record<FlashKind, (summary: string, options?: FlashOptions) => void>>;

const STYLE = {
  success: { color: "success", icon: "i-lucide-circle-check", duration: 3000 },
  info: { color: "info", icon: "i-lucide-info", duration: 5000 },
  warn: { color: "warning", icon: "i-lucide-triangle-alert", duration: 0 },
  error: { color: "error", icon: "i-lucide-circle-x", duration: 0 },
} as const;

/** Call in setup (inside UApp); the returned functions can be used later, from any handler. */
export function useFlash(): Flash {
  const toast = useToast();
  const show =
    (kind: FlashKind) =>
    (summary: string, options: FlashOptions = {}): void => {
      const style = STYLE[kind];
      const stays = style.duration === 0 || options.persistent === true;
      toast.add({
        title: summary,
        ...(options.detail === undefined ? {} : { description: options.detail }),
        ...(options.id === undefined ? {} : { id: options.id }),
        color: style.color,
        icon: style.icon,
        // Reka's toast never closes by itself with an infinite duration.
        duration: stays ? Number.POSITIVE_INFINITY : style.duration,
        progress: !stays,
      });
      announce(options.detail === undefined ? summary : `${summary} ${options.detail}`, kind === "warn" || kind === "error");
    };
  return { success: show("success"), info: show("info"), warn: show("warn"), error: show("error") };
}
