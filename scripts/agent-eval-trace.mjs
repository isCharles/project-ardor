/**
 * Fetches a complete agent trace by following offset pagination.
 *
 * A single page of 128 spans used to be returned silently, so a long run could be
 * scored on a prefix of its tools. Evaluation must treat an incomplete trace as an
 * environment error: returning a partial span list would inflate or hide failures.
 */
export const DEFAULT_TRACE_PAGE_SIZE = 500;
export const DEFAULT_TRACE_MAX_PAGES = 20;

export async function fetchCompleteTrace(fetchPage, requestId, options = {}) {
  const { pageSize = DEFAULT_TRACE_PAGE_SIZE, maxPages = DEFAULT_TRACE_MAX_PAGES } = options;
  const first = await requestPage(fetchPage, requestId, 0, pageSize);
  if (!first || !Array.isArray(first.spans) || first.spans.length === 0) return null;
  // A backend without pagination metadata cannot prove the trace is complete.
  if (!Number.isInteger(first.totalSpans) || first.totalSpans < 0) return null;

  const total = first.totalSpans;
  const spans = [...first.spans];
  if (spans.length > total) return null;

  let nextOffset = first.nextOffset ?? null;
  let pages = 1;
  while (nextOffset != null) {
    if (!Number.isInteger(nextOffset) || nextOffset <= 0 || pages >= maxPages) return null;
    const page = await requestPage(fetchPage, requestId, nextOffset, pageSize);
    if (!page || !Array.isArray(page.spans)) return null;
    spans.push(...page.spans);
    if (spans.length > total) return null;
    nextOffset = page.nextOffset ?? null;
    pages += 1;
  }

  if (spans.length !== total) return null;
  return { ...first, spans, truncated: false, fetchedPages: pages };
}

async function requestPage(fetchPage, requestId, offset, limit) {
  let response;
  try {
    response = await fetchPage(`/api/agent/runs/${requestId}/trace?offset=${offset}&limit=${limit}`);
  } catch {
    return null;
  }
  if (!response || !response.ok) return null;
  try {
    return await response.json();
  } catch {
    return null;
  }
}
