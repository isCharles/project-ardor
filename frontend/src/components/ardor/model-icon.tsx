import { Cpu } from "lucide-react";

const iconRules: Array<[RegExp, string, string]> = [
  [/(?:^|[/:\s-])(?:gpt|chatgpt|o[134])(?:[/:\s-]|$)|openai/i, "lobe-openai.svg", "#e7f6ec"],
  [/claude|anthropic|opus|sonnet|haiku/i, "color-claude.svg", "#fdf0e8"],
  [/deepseek/i, "color-deepseek.svg", "#e8f1ff"],
  [/gemini|google|palm/i, "color-gemini.svg", "#eef0ff"],
  [/qwen|qwq|tongyi|alibaba/i, "color-qwen.svg", "#f4eaff"],
  [/seed|doubao|ark/i, "color-doubao.svg", "#e8f7ff"],
  [/bytedance|volcengine/i, "color-bytedance.svg", "#e8f7ff"],
  [/kimi|moonshot/i, "color-kimi.svg", "#eef0f5"],
  [/glm|zhipu|chatglm/i, "color-zhipu.svg", "#eaf2ff"],
  [/grok|xai|x-ai/i, "lobe-grok.svg", "#f1f1f1"],
  [/llama|meta/i, "color-meta.svg", "#eef3ff"],
  [/mistral|mixtral|codestral/i, "color-mistral.svg", "#fff3e6"],
];

function iconFor(label: string) {
  return iconRules.find(([pattern]) => pattern.test(label));
}

export function ModelIcon({ label, size = "sm" }: { label: string; size?: "sm" | "md" }) {
  const match = iconFor(label);
  const frame = size === "md" ? "size-8 rounded-xl" : "size-7 rounded-lg";
  const mark = size === "md" ? "size-[18px]" : "size-4";
  if (!match) {
    return <span className={`grid ${frame} shrink-0 place-items-center bg-stone-100 text-stone-400 ring-1 ring-stone-200/70`}>
      <Cpu className={size === "md" ? "size-4" : "size-3.5"} />
    </span>;
  }
  const [, icon, tint] = match;
  return <span className={`grid ${frame} shrink-0 place-items-center ring-1 ring-black/5`} style={{ backgroundColor: tint }}>
    <span aria-hidden className={`${mark} bg-contain bg-center bg-no-repeat`} style={{ backgroundImage: `url(/model-icons/${icon})` }} />
  </span>;
}
