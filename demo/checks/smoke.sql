\set ON_ERROR_STOP on
\pset pager off
SELECT version();
SELECT extname, extversion FROM pg_extension WHERE extname = 'hypopg';

DO $$
BEGIN
    IF current_user <> 'indexbudget_reader' THEN
        RAISE EXCEPTION 'Run the smoke check as indexbudget_reader';
    END IF;
    IF current_setting('server_version_num')::integer / 10000 <> 16 OR
       (SELECT extversion FROM pg_extension WHERE extname = 'hypopg') IS DISTINCT FROM '1.4.2' THEN
        RAISE EXCEPTION 'Unexpected PostgreSQL/HypoPG version';
    END IF;
    IF (SELECT count(*) FROM public.records) <> 200000 OR
       (SELECT count(*) FROM public.groups) <> 1000 OR
       (SELECT count(*) FROM public.wide_records) <> 10000 THEN
        RAISE EXCEPTION 'Unexpected seed row counts';
    END IF;
    IF (SELECT count(*) FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'wide_records') <> 100 THEN
        RAISE EXCEPTION 'Wide table must contain 100 columns';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_stats WHERE schemaname = 'public' AND tablename = 'records') THEN
        RAISE EXCEPTION 'Seed statistics are absent';
    END IF;
    IF has_table_privilege(current_user, 'public.records', 'INSERT') OR
       has_schema_privilege(current_user, 'public', 'CREATE') OR
       (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION 'Analysis role has excessive privileges';
    END IF;
END $$;

-- One connection for baseline, hypothetical index, size and cleanup.
SELECT hypopg_reset();
DO $$
DECLARE
    baseline json;
    proposed json;
    restored json;
    candidate_oid oid;
    estimated_bytes bigint;
    real_indexes bigint;
    query_sql text := 'SELECT id, value FROM public.records WHERE group_id = 42 ORDER BY created_at DESC LIMIT 20';
BEGIN
    SELECT count(*) INTO real_indexes FROM pg_index WHERE indrelid = 'public.records'::regclass;
    EXECUTE 'EXPLAIN (FORMAT JSON) ' || query_sql INTO baseline;
    SELECT indexrelid INTO candidate_oid FROM hypopg_create_index(
        'CREATE INDEX ON public.records (group_id, created_at DESC)');
    SELECT hypopg_relation_size(candidate_oid) INTO estimated_bytes;
    IF estimated_bytes IS NULL OR estimated_bytes <= 0 THEN
        RAISE EXCEPTION 'HypoPG returned no positive size';
    END IF;
    EXECUTE 'EXPLAIN (FORMAT JSON) ' || query_sql INTO proposed;
    RAISE NOTICE 'baseline cost=%, candidate cost=%, estimated bytes=%',
        baseline->0->'Plan'->>'Total Cost', proposed->0->'Plan'->>'Total Cost', estimated_bytes;
    RAISE NOTICE 'baseline plan=%', baseline;
    RAISE NOTICE 'candidate plan=%', proposed;
    -- A lower cost is observed, not assumed by this infrastructure check.
    IF (SELECT count(*) FROM pg_index WHERE indrelid = 'public.records'::regclass) <> real_indexes THEN
        RAISE EXCEPTION 'Real indexes changed';
    END IF;
    PERFORM hypopg_reset();
    IF EXISTS (SELECT 1 FROM hypopg_list_indexes) THEN
        RAISE EXCEPTION 'Hypotheses remain after reset';
    END IF;
    EXECUTE 'EXPLAIN (FORMAT JSON) ' || query_sql INTO restored;
    IF baseline->0->'Plan'->>'Total Cost' IS DISTINCT FROM restored->0->'Plan'->>'Total Cost' THEN
        RAISE EXCEPTION 'Baseline cost was not restored';
    END IF;
END $$;
SELECT 'demo smoke check passed' AS result;
