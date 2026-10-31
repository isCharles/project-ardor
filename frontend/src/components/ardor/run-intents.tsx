import { runIntents } from "@/lib/run-intents";

/** The model's own one-liners about what it is doing next, kept out of the answer body. */
export function RunIntents({ steps }: { steps: Array<{ label: string; status?: string | null }> | null | undefined }) {
  const lines = runIntents(steps);
  if (!lines.length) return null;
  return <div data-run-intents className="mb-2 space-y-0.5">
    {lines.map((line, index) => (
      <p key={`${index}-${line}`} className="flex items-start gap-2 text-xs leading-6 text-stone-400">
        <span aria-hidden className="mt-[9px] size-1 shrink-0 rounded-full bg-stone-300" />
        <span className="min-w-0 break-words">{line}</span>
      </p>
    ))}
  </div>;
}
