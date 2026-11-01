-- Preserve a single participant identity per Case and prevent cross-Case
-- Decision/Attention references. Fail explicitly if legacy data is ambiguous.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM case_participants
        GROUP BY case_id, actor_type, agent_id, user_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V16 cannot enforce participant identity uniqueness: duplicate case_participants rows exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM decisions d
        JOIN work_items wi ON wi.work_item_id=d.work_item_id
        WHERE d.work_item_id IS NOT NULL AND d.case_id<>wi.case_id
    ) THEN
        RAISE EXCEPTION
            'V16 cannot enforce Decision scope: a decision references a Work Item from another Case';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM attention_requests ar
        JOIN work_items wi ON wi.work_item_id=ar.work_item_id
        WHERE ar.work_item_id IS NOT NULL AND ar.case_id<>wi.case_id
    ) THEN
        RAISE EXCEPTION
            'V16 cannot enforce Attention scope: an attention request references a Work Item from another Case';
    END IF;
END;
$$;

ALTER TABLE case_participants
    DROP CONSTRAINT uk_case_participant,
    ADD CONSTRAINT uk_case_participant UNIQUE NULLS NOT DISTINCT
        (case_id, actor_type, agent_id, user_id);

ALTER TABLE decisions
    ADD CONSTRAINT fk_decisions_work_item_case
        FOREIGN KEY (work_item_id, case_id) REFERENCES work_items(work_item_id, case_id);

ALTER TABLE attention_requests
    ADD CONSTRAINT fk_ar_work_item_case
        FOREIGN KEY (work_item_id, case_id) REFERENCES work_items(work_item_id, case_id);
