\set ON_ERROR_STOP on
SET statement_timeout = '10s';
SELECT hypopg_reset();
DO $$
DECLARE
    candidate_oid oid;
    plan json;
BEGIN
    IF current_setting('server_version_num')::integer / 10000 <> 16 THEN
        RAISE EXCEPTION 'Expected PostgreSQL 16';
    END IF;
    IF (SELECT extversion FROM pg_extension WHERE extname = 'hypopg') IS DISTINCT FROM '1.4.2' THEN
        RAISE EXCEPTION 'Expected HypoPG 1.4.2';
    END IF;
    SELECT indexrelid INTO candidate_oid FROM hypopg_create_index(
        'CREATE INDEX ON public.records (group_id, created_at DESC)');
    IF hypopg_relation_size(candidate_oid) IS NULL THEN
        RAISE EXCEPTION 'Missing HypoPG size';
    END IF;
    EXECUTE 'EXPLAIN (FORMAT JSON) SELECT id, value FROM public.records WHERE group_id = 42 ORDER BY created_at DESC LIMIT 20'
        INTO plan;
    IF plan->0->'Plan'->>'Total Cost' IS NULL THEN
        RAISE EXCEPTION 'Missing planner cost';
    END IF;
    PERFORM hypopg_reset();
    IF EXISTS (SELECT 1 FROM public.hypopg_list_indexes) THEN
        RAISE EXCEPTION 'Hypotheses remain after reset';
    END IF;
END $$;
SELECT 'stand check passed' AS result;
