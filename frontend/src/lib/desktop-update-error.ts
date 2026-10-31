export type UpdateErrorKind = "legacy-feed" | "not-found" | "access" | "network" | "incomplete" | "metadata" | "other";

const KNOWN_KINDS: UpdateErrorKind[] = ["legacy-feed", "not-found", "access", "network", "incomplete", "metadata", "other"];

// Newer desktop clients report the kind explicitly; the regexes stay for clients
// that only forward a message, and for messages the desktop could not classify.
export function describeDesktopUpdateError(
  message: string,
  hint?: { errorKind?: string; detail?: string },
): { kind: UpdateErrorKind; detail: string } {
  const raw = String(message ?? "");
  // Older desktop clients forwarded the entire GitHub response, including
  // Set-Cookie. Never put response headers into the rendered diagnostic.
  const withoutHeaders = String(hint?.detail || raw).split(/\bHeaders\s*:\s*\{/i)[0]
    .replace(/\b(?:set-cookie|cookie|authorization)\s*:[^\r\n]*/gi, "[redacted]")
    .replace(/\b(?:github_pat_|ghp_|sk-)[A-Za-z0-9_-]{10,}/g, "[redacted]")
    .trim();
  const detail = withoutHeaders.length > 800 ? `${withoutHeaders.slice(0, 800)}…` : withoutHeaders;
  const hinted = KNOWN_KINDS.find(kind => kind === hint?.errorKind);
  const kind: UpdateErrorKind = hinted ?? (/releases\.atom/i.test(raw) && /\b404\b/.test(raw) ? "legacy-feed"
    : /\b404\b/.test(raw) ? "not-found"
    : /\b(?:401|403)\b/.test(raw) ? "access"
    : /ENOTFOUND|EAI_AGAIN|ECONNRESET|ETIMEDOUT|network|fetch failed/i.test(raw) ? "network"
    : "other");
  return { kind, detail };
}
