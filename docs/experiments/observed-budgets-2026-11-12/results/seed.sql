\set ON_ERROR_STOP on
\getenv reader_password INDEXBUDGET_READER_PASSWORD
BEGIN;
CREATE EXTENSION hypopg;

-- Fixed values and row counts: no random(), current dates or external datasets.
CREATE TABLE public.records (
    id bigint PRIMARY KEY,
    group_id integer NOT NULL,
    value integer NOT NULL,
    created_at timestamp NOT NULL,
    payload text NOT NULL
);
INSERT INTO public.records
SELECT i, (i % 1000)::integer, ((i * 37) % 997)::integer,
       timestamp '2026-01-01 00:00:00' + i * interval '1 second',
       repeat(md5(i::text), 4)
FROM generate_series(1, 200000) AS g(i);

-- Second table for later write-rate and JOIN scenarios; PoC uses records only.
CREATE TABLE public.groups (
    id integer PRIMARY KEY,
    category integer NOT NULL,
    name text NOT NULL
);
INSERT INTO public.groups
SELECT i, i % 10, 'group-' || i FROM generate_series(0, 999) AS g(i);

-- Exactly 100 columns. No generator limit is imposed on the catalog.
DO $$
DECLARE
    columns_sql text;
    values_sql text;
BEGIN
    SELECT string_agg(format('c%s integer NOT NULL', n), ', ' ORDER BY n),
           string_agg(format('(i * %s) %% 1009', n), ', ' ORDER BY n)
      INTO columns_sql, values_sql FROM generate_series(1, 100) AS s(n);
    EXECUTE 'CREATE TABLE public.wide_records (' || columns_sql || ')';
    EXECUTE 'INSERT INTO public.wide_records SELECT ' || values_sql ||
            ' FROM generate_series(1, 10000) AS g(i)';
END $$;

-- Statistics are prepared by the demo owner, never by the analysis role.
ANALYZE public.records;
ANALYZE public.groups;
ANALYZE public.wide_records;

CREATE ROLE indexbudget_reader LOGIN PASSWORD :'reader_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE indexbudget FROM PUBLIC;
GRANT CONNECT ON DATABASE indexbudget TO indexbudget_reader;
GRANT USAGE ON SCHEMA public TO indexbudget_reader;
GRANT SELECT ON public.records, public.groups, public.wide_records TO indexbudget_reader;
GRANT SELECT ON public.hypopg_list_indexes TO indexbudget_reader;

CREATE SCHEMA demo_meta;
CREATE TABLE demo_meta.seed_status (
    version text PRIMARY KEY,
    ready boolean NOT NULL
);
INSERT INTO demo_meta.seed_status VALUES ('v1', true);
GRANT USAGE ON SCHEMA demo_meta TO indexbudget_reader;
GRANT SELECT ON demo_meta.seed_status TO indexbudget_reader;
COMMIT;
