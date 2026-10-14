// Instants for FwLocalTime (ui-kit local-time.md): the viewer's locale and time zone for the text, UTC
// to whole seconds for the title (what the server would have rendered).

/** `2026-10-06 08:30:00 UTC` (or `2026-10-06` for a date), or undefined when not an instant. */
export function utcText(iso: string, dateOnly: boolean): string | undefined {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return undefined;
  }
  const text = date.toISOString();
  return dateOnly ? text.slice(0, 10) : `${text.slice(0, 10)} ${text.slice(11, 19)} UTC`;
}

/** The instant in the viewer's locale (or the given one) and time zone (or the given one). */
export function localText(iso: string, options: { dateOnly: boolean; locale?: string; timeZone?: string }): string | undefined {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return undefined;
  }
  const format = new Intl.DateTimeFormat(options.locale, {
    dateStyle: "medium",
    ...(options.dateOnly ? {} : { timeStyle: "medium" }),
    ...(options.timeZone === undefined ? {} : { timeZone: options.timeZone }),
  });
  return format.format(date);
}
