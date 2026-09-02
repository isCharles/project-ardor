export type ApiErrorBody = {
  code?: string;
  message?: string;
  fields?: Record<string, string>;
  retryable?: boolean;
};

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: ApiErrorBody,
  ) {
    super(body.message ?? `请求失败（${status}）`);
  }
}

async function csrfHeaders(): Promise<Record<string, string>> {
  const response = await fetch("/api/auth/csrf", {
    credentials: "include",
    cache: "no-store",
  });
  if (!response.ok) {
    throw new ApiError(response.status, await parseBody(response));
  }
  const csrf = (await response.json()) as { token: string; headerName: string };
  return { [csrf.headerName]: csrf.token };
}

async function parseBody(response: Response): Promise<ApiErrorBody> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    return { message: `请求失败（${response.status}）` };
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const unsafe = !["GET", "HEAD", "OPTIONS"].includes(method);
  const headers = new Headers(init.headers);
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (unsafe) {
    Object.entries(await csrfHeaders()).forEach(([name, value]) => headers.set(name, value));
  }

  const response = await fetch(path, {
    ...init,
    headers,
    credentials: "include",
    cache: "no-store",
  });
  if (!response.ok) {
    throw new ApiError(response.status, await parseBody(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export async function streamApi<T>(
  path: string,
  body: unknown,
  onEvent: (event: T) => void,
): Promise<void> {
  const headers = new Headers({ "Content-Type": "application/json", Accept: "text/event-stream" });
  Object.entries(await csrfHeaders()).forEach(([name, value]) => headers.set(name, value));
  const response = await fetch(path, {
    method: "POST", headers, body: JSON.stringify(body), credentials: "include", cache: "no-store",
  });
  if (!response.ok) throw new ApiError(response.status, await parseBody(response));
  if (!response.body) throw new Error("浏览器无法读取流式响应");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replaceAll("\r\n", "\n");
    let boundary = buffer.indexOf("\n\n");
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
      const data = block.split("\n").filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart()).join("\n");
      if (data) {
        onEvent(JSON.parse(data) as T);
        // A proxy may coalesce several SSE records into one network chunk.
        // Yielding one frame prevents React from painting only the final state.
        if (typeof requestAnimationFrame === "function" && document.visibilityState === "visible") {
          await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
        }
      }
      boundary = buffer.indexOf("\n\n");
    }
    if (done) break;
  }
}
