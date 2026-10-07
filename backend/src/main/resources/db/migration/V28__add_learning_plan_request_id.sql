-- Nullable for plans created before request IDs were introduced.
ALTER TABLE learning_plans ADD COLUMN request_id UUID;
ALTER TABLE learning_plans ADD COLUMN request_hash VARCHAR(64);
ALTER TABLE learning_plans ADD CONSTRAINT ck_learning_plan_request_pair
    CHECK ((request_id IS NULL AND request_hash IS NULL)
        OR (request_id IS NOT NULL AND request_hash IS NOT NULL));
CREATE UNIQUE INDEX uq_learning_plans_user_request
    ON learning_plans (user_id, request_id) WHERE request_id IS NOT NULL;
