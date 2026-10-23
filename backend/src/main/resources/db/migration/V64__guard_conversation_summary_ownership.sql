-- Add the same composite ownership guard as conversations/messages.
-- Deliberately fail rather than delete or reassign any inconsistent existing summaries.
ALTER TABLE conversation_summaries
    ADD CONSTRAINT fk_conversation_summaries_owner
    FOREIGN KEY (conversation_id, user_id) REFERENCES conversations(id, user_id) ON DELETE CASCADE;
