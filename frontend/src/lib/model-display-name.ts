/** Presentation only. Never use the result as a provider model ID or selection key. */
export function modelDisplayName(raw: string): string {
  const value = raw.trim();
  const brands: Record<string, string> = { claude: "Claude", deepseek: "DeepSeek", gpt: "GPT", gemini: "Gemini", qwen: "Qwen", kimi: "Kimi", glm: "GLM", grok: "Grok", llama: "Llama", mistral: "Mistral" };
  const parts = value.split("-");
  const brand = brands[parts[0]?.toLowerCase()];
  if (!brand) return value.replace(/^\p{L}/u, letter => letter.toUpperCase());
  const words = [brand];
  for (let index = 1; index < parts.length; index++) {
    let part = parts[index];
    // A dated snapshot (e.g. 20251001) must not become part of a decimal version.
    if (/^v?\d{1,2}$/i.test(part) && /^\d{1,2}$/.test(parts[index + 1] ?? "")) part += `.${parts[++index]}`;
    words.push(part.replace(/^\p{L}/u, letter => letter.toUpperCase()));
  }
  return words.join(" ");
}
