-- Context compaction gets its own span kind so it cannot be mistaken for an ordinary model call.
ALTER TABLE agent_trace_spans DROP CONSTRAINT ck_agent_trace_kind;
ALTER TABLE agent_trace_spans ADD CONSTRAINT ck_agent_trace_kind CHECK (
    kind IN ('RUN', 'LLM', 'TOOL', 'CONTEXT', 'COMPACTION', 'THOUGHT', 'RETRY', 'ASYNC')
);

COMMENT ON COLUMN agent_trace_spans.kind IS
    'Span kind: RUN/LLM/TOOL/CONTEXT/COMPACTION/THOUGHT/RETRY/ASYNC. COMPACTION is a summarised-history model call; THOUGHT is a phase summary span, never raw chain-of-thought.';
