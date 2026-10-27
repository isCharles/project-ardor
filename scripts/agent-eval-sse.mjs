/** Incremental SSE parser; chunks can split UTF-8 text, lines and event boundaries. */
export class SseParser {
  #buffer = "";
  #data = [];
  #emit;
  constructor(emit) { this.#emit = emit; }
  push(chunk, final = false) {
    this.#buffer += chunk;
    let match;
    while ((match = /\r\n|\r|\n/.exec(this.#buffer))) {
      // A trailing CR can be the first half of CRLF in the next chunk.
      if (!final && match[0] === "\r" && match.index === this.#buffer.length - 1) break;
      const line = this.#buffer.slice(0, match.index);
      this.#buffer = this.#buffer.slice(match.index + match[0].length);
      this.#line(line);
    }
    if (final) {
      if (this.#buffer) this.#line(this.#buffer);
      this.#buffer = "";
      // An incomplete final event is not dispatched: SSE requires a blank line.
      this.#data = [];
    }
  }
  #line(line) {
    if (line === "") {
      if (this.#data.length) this.#emit(this.#data.join("\n"));
      this.#data = [];
      return;
    }
    if (line.startsWith(":")) return;
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "data") this.#data.push(value);
  }
}
