/** Evaluate recorded execution, not just the model's request to invoke a tool. */
export function evaluateCase(item, trace, answer) {
  const tools = Array.isArray(trace?.spans) ? trace.spans.filter((span) => span.kind === "TOOL") : [];
  const failures = [];
  for (const name of item.expectedTools ?? []) {
    const calls = tools.filter((span) => span.name === name);
    if (!calls.length) failures.push(`expected tool ${name} was not recorded`);
    else if (!calls.some((span) => span.status === "COMPLETED")) failures.push(`expected tool ${name} did not complete`);
  }
  for (const name of item.forbiddenTools ?? []) {
    if (tools.some((span) => span.name === name)) failures.push(`forbidden tool ${name} was attempted`);
  }
  if (Array.isArray(item.allowedTools)) {
    for (const name of new Set(tools.map((span) => span.name))) {
      if (!item.allowedTools.includes(name)) failures.push(`unexpected tool ${name} was attempted`);
    }
  }
  if (item.mustContain && !String(answer ?? "").includes(item.mustContain)) failures.push("answer assertion failed");
  if (trace?.status !== "COMPLETED") failures.push("run did not complete");
  return { failures, toolNames: tools.map((span) => span.name) };
}
